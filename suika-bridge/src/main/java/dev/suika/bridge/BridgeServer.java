package dev.suika.bridge;

import dev.suika.env.ActionSpace;
import dev.suika.env.ObservationMode;
import dev.suika.env.RewardConfig;
import dev.suika.env.SuikaEnv;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * TCP server wrapping {@link GymBridge} for the Python {@code backend="java"} path.
 *
 * <p>Wire protocol (little-endian, both directions) matches {@link ObservationCodec}
 * and {@code python/suika/bridge.py}:
 * <pre>
 *   request:  [int32 n][float32 × n]
 *     reset → [0, seed]
 *     step  → [1, action]   (discrete: action bin index; continuous: [-1, 1])
 *     close → [2]
 *   reset response: observation floats
 *   step  response: [obs… | reward | terminated | truncated | merges_this_step]
 * </pre>
 *
 * <p>Start from the app CLI: {@code ./gradlew :suika-app:run --args="--bridge-port 50052"}
 */
public final class BridgeServer implements Closeable {

    public static final float CMD_RESET = 0f;
    public static final float CMD_STEP  = 1f;
    public static final float CMD_CLOSE = 2f;

    public static final int DEFAULT_PORT = 50052;
    public static final int DEFAULT_ACTION_BINS = 32;

    private final int port;
    private final int actionBins;
    private final boolean continuous;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private ServerSocket serverSocket;
    private Thread acceptThread;

    public BridgeServer(int port) {
        this(port, DEFAULT_ACTION_BINS, false);
    }

    public BridgeServer(int port, int actionBins, boolean continuous) {
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("Invalid bridge port: " + port);
        }
        if (actionBins < 2) {
            throw new IllegalArgumentException("actionBins must be >= 2");
        }
        this.port = port;
        this.actionBins = actionBins;
        this.continuous = continuous;
    }

    public int port() { return port; }

    public boolean isRunning() { return running.get(); }

    /** Bind and accept connections on a daemon thread. Returns when the socket is listening. */
    public synchronized void start() throws IOException {
        if (running.get()) return;
        serverSocket = new ServerSocket(port);
        running.set(true);
        acceptThread = new Thread(this::acceptLoop, "suika-bridge-" + port);
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    /** Block the calling thread serving one client at a time until {@link #close()}. */
    public void serveBlocking() throws IOException {
        start();
        try {
            if (acceptThread != null) acceptThread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket client = serverSocket.accept();
                try {
                    handleClient(client);
                } catch (EOFException | SocketException closed) {
                    // client disconnected — wait for the next one
                } catch (IOException e) {
                    if (running.get()) {
                        System.err.println("[suika-bridge] client error: " + e.getMessage());
                    }
                } finally {
                    try { client.close(); } catch (IOException ignored) {}
                }
            } catch (SocketException closed) {
                break;
            } catch (IOException e) {
                if (running.get()) {
                    System.err.println("[suika-bridge] accept error: " + e.getMessage());
                }
                break;
            }
        }
    }

    private GymBridge newGym() {
        ActionSpace space = continuous
                ? new ActionSpace.Continuous()
                : new ActionSpace.Discrete(actionBins);
        return new GymBridge(new SuikaEnv(ObservationMode.STATE, space, RewardConfig.defaultConfig()));
    }

    void handleClient(Socket client) throws IOException {
        client.setTcpNoDelay(true);
        DataInputStream in = new DataInputStream(client.getInputStream());
        DataOutputStream out = new DataOutputStream(client.getOutputStream());
        GymBridge gym = newGym();
        boolean episodeOpen = false;

        while (running.get() && !client.isClosed()) {
            float[] frame = readFrame(in);
            if (frame.length == 0) {
                throw new IOException("Empty bridge frame");
            }
            float cmd = frame[0];
            if (frame.length == 1 && cmd != CMD_CLOSE) {
                // Legacy single-float: treat as reset when no episode, else step.
                if (!episodeOpen) {
                    float[] obs = gym.reset((long) cmd);
                    writeFrame(out, obs);
                    episodeOpen = true;
                } else {
                    GymBridge.Transition t = gym.step(cmd);
                    writeFrame(out, encodeTransition(t));
                    if (t.done()) episodeOpen = false;
                }
                continue;
            }
            if (cmd == CMD_CLOSE) {
                break;
            }
            if (cmd == CMD_RESET) {
                long seed = frame.length > 1 ? (long) frame[1] : 0L;
                float[] obs = gym.reset(seed);
                writeFrame(out, obs);
                episodeOpen = true;
                continue;
            }
            if (cmd == CMD_STEP) {
                if (!episodeOpen) {
                    throw new IOException("step before reset");
                }
                double action = frame.length > 1 ? frame[1] : 0.0;
                GymBridge.Transition t = gym.step(action);
                writeFrame(out, encodeTransition(t));
                if (t.done()) episodeOpen = false;
                continue;
            }
            throw new IOException("Unknown bridge command: " + cmd);
        }
    }

    static float[] encodeTransition(GymBridge.Transition t) {
        float[] obs = t.observation();
        float[] out = new float[obs.length + 4];
        System.arraycopy(obs, 0, out, 0, obs.length);
        out[obs.length]     = (float) t.reward();
        out[obs.length + 1] = t.terminated() ? 1f : 0f;
        out[obs.length + 2] = t.truncated() ? 1f : 0f;
        out[obs.length + 3] = t.mergesThisStep();
        return out;
    }

    static float[] readFrame(DataInputStream in) throws IOException {
        int n = Integer.reverseBytes(in.readInt()); // little-endian length
        if (n < 0 || n > 100_000) {
            throw new IOException("Suspicious frame length: " + n);
        }
        byte[] raw = in.readNBytes(n * Float.BYTES);
        if (raw.length != n * Float.BYTES) {
            throw new EOFException("Truncated frame");
        }
        ByteBuffer buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        float[] values = new float[n];
        for (int i = 0; i < n; i++) values[i] = buf.getFloat();
        return values;
    }

    static void writeFrame(DataOutputStream out, float[] values) throws IOException {
        byte[] encoded = ObservationCodec.encode(values);
        out.write(encoded);
        out.flush();
    }

    @Override
    public synchronized void close() {
        running.set(false);
        if (serverSocket != null) {
            try { serverSocket.close(); } catch (IOException ignored) {}
            serverSocket = null;
        }
        if (acceptThread != null) {
            try { acceptThread.join(2_000); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            acceptThread = null;
        }
    }

    /**
     * Parse {@code --bridge-port N} (and optional {@code --action-bins}, {@code --continuous})
     * from app args. Returns {@code null} when the flag is absent.
     */
    public static BridgeServer fromArgs(String[] args) {
        Integer port = null;
        int bins = DEFAULT_ACTION_BINS;
        boolean continuous = false;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if ("--bridge-port".equals(a) && i + 1 < args.length) {
                port = Integer.parseInt(args[++i]);
            } else if (a.startsWith("--bridge-port=")) {
                port = Integer.parseInt(a.substring("--bridge-port=".length()));
            } else if ("--action-bins".equals(a) && i + 1 < args.length) {
                bins = Integer.parseInt(args[++i]);
            } else if ("--continuous".equals(a)) {
                continuous = true;
            }
        }
        return port == null ? null : new BridgeServer(port, bins, continuous);
    }

    /** Standalone entry used by tests and {@code --bridge-port} in suika-app. */
    public static void main(String[] args) throws Exception {
        BridgeServer server = fromArgs(args);
        if (server == null) {
            server = new BridgeServer(DEFAULT_PORT);
        }
        System.out.println("Suika bridge listening on port " + server.port()
                + " (actionBins=" + server.actionBins
                + ", continuous=" + server.continuous + ")");
        System.out.println("Python: suika.make(backend=\"java\", host=\"localhost\", port="
                + server.port() + ")");
        Runtime.getRuntime().addShutdownHook(new Thread(server::close, "bridge-shutdown"));
        server.serveBlocking();
    }
}
