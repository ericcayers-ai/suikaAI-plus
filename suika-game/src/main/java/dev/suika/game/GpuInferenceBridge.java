package dev.suika.game;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Runs a trained {@link dev.suika.ai.MlpPolicy}'s forward pass on the GPU via a persistent
 * Python (PyTorch/CUDA) worker from the managed venv. The worker replicates the EXACT same
 * computation as {@code MlpPolicy.forward} — {@code tanh(W1·x + b1)} then {@code W2·h + b2},
 * argmax — using the identical flat weight layout, so a GPU decision never diverges from
 * the JVM one; it's the same math, just on CUDA.
 *
 * <p>Deliberately used only on the <b>load-once, infer-many</b> playback path
 * ({@link AiSlotPlayer#load}) — a saved model played back a few times a second — where the
 * ~1 ms IPC per query is irrelevant. The high-frequency training eval loop stays on the
 * JVM, where a direct in-process forward pass of a 64-hidden MLP is far faster than any
 * GPU round-trip. Every failure mode (no venv, torch import error, timeout, crash) marks
 * the bridge dead so the caller falls straight back to the exact JVM path — GPU inference
 * is a bonus, never a dependency.
 */
final class GpuInferenceBridge implements AutoCloseable {

    private Process process;
    private Writer stdin;
    private BufferedReader stdout;
    private Path weightsFile;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "gpu-infer-io"); t.setDaemon(true); return t;
    });
    private volatile boolean alive = false;

    /** Starts a worker for the given policy on {@code device} ("cuda"/"cpu"). Returns a
     *  bridge whose {@link #healthy()} is false if startup failed for any reason. */
    static GpuInferenceBridge start(int inputSize, int hiddenSize, int outputSize,
                                    double[] weights, String device) {
        GpuInferenceBridge b = new GpuInferenceBridge();
        try {
            b.boot(inputSize, hiddenSize, outputSize, weights, device);
        } catch (Throwable t) {
            b.alive = false;
            b.close();
        }
        return b;
    }

    private void boot(int inputSize, int hiddenSize, int outputSize, double[] weights, String device)
            throws Exception {
        if (!PythonSetup.isReady()) return;
        weightsFile = Files.createTempFile("suika-gpu-weights", ".txt");
        StringBuilder sb = new StringBuilder();
        for (double w : weights) sb.append(w).append('\n');
        Files.writeString(weightsFile, sb.toString(), StandardCharsets.UTF_8);

        process = new ProcessBuilder(
                PythonSetup.venvPython().toString(), "-c", WORKER_SCRIPT,
                Integer.toString(inputSize), Integer.toString(hiddenSize),
                Integer.toString(outputSize), weightsFile.toString(), device)
                .redirectErrorStream(false)
                .start();
        stdin = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
        stdout = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));

        // torch import + CUDA init is slow the first time — allow a generous startup window.
        String ready = readLineTimed(25, TimeUnit.SECONDS);
        alive = ready != null && ready.startsWith("READY");
    }

    boolean healthy() { return alive && process != null && process.isAlive(); }

    /** Argmax action for one observation, or -1 if the bridge is unhealthy / times out
     *  (the caller then uses its JVM fallback). Marks the bridge dead on any failure. */
    int argmax(float[] obs) {
        if (!healthy()) return -1;
        try {
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < obs.length; i++) { if (i > 0) line.append(' '); line.append(obs[i]); }
            line.append('\n');
            stdin.write(line.toString());
            stdin.flush();
            String out = readLineTimed(2, TimeUnit.SECONDS);
            if (out == null) { alive = false; return -1; }
            return Integer.parseInt(out.trim());
        } catch (Throwable t) {
            alive = false;
            return -1;
        }
    }

    private String readLineTimed(long timeout, TimeUnit unit) {
        Future<String> f = io.submit(() -> stdout.readLine());
        try {
            return f.get(timeout, unit);
        } catch (Throwable t) {
            f.cancel(true);
            return null;
        }
    }

    @Override
    public void close() {
        alive = false;
        try { if (stdin != null) { stdin.write("QUIT\n"); stdin.flush(); } } catch (Exception ignored) { }
        try { if (process != null) process.destroy(); } catch (Exception ignored) { }
        io.shutdownNow();
        try { if (weightsFile != null) Files.deleteIfExists(weightsFile); } catch (Exception ignored) { }
    }

    // Inline worker. argv: inputSize hiddenSize outputSize weightsPath device
    private static final String WORKER_SCRIPT =
        "import sys\n" +
        "inp,hid,out=int(sys.argv[1]),int(sys.argv[2]),int(sys.argv[3])\n" +
        "wpath,dev=sys.argv[4],sys.argv[5]\n" +
        "import torch\n" +
        "vals=[]\n" +
        "for ln in open(wpath):\n" +
        "    ln=ln.strip()\n" +
        "    if ln and '=' not in ln and not ln.startswith('#'): vals.append(float(ln))\n" +
        "w=torch.tensor(vals,dtype=torch.float32)\n" +
        "device='cuda' if (dev=='cuda' and torch.cuda.is_available()) else 'cpu'\n" +
        "W1=w[0:hid*inp].reshape(hid,inp).to(device)\n" +
        "b1=w[hid*inp:hid*inp+hid].to(device)\n" +
        "o=hid*inp+hid\n" +
        "W2=w[o:o+out*hid].reshape(out,hid).to(device)\n" +
        "b2=w[o+out*hid:o+out*hid+out].to(device)\n" +
        "sys.stdout.write('READY\\n'); sys.stdout.flush()\n" +
        "for line in sys.stdin:\n" +
        "    line=line.strip()\n" +
        "    if not line: continue\n" +
        "    if line=='QUIT': break\n" +
        "    x=torch.tensor([float(v) for v in line.split()],dtype=torch.float32,device=device)\n" +
        "    h=torch.tanh(W1@x + b1)\n" +
        "    y=W2@h + b2\n" +
        "    sys.stdout.write(str(int(torch.argmax(y).item()))+'\\n'); sys.stdout.flush()\n";
}
