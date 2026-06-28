package dev.suika.bridge;

import dev.suika.env.ActionSpace;
import dev.suika.env.ObservationMode;
import dev.suika.env.RewardConfig;
import dev.suika.env.SuikaEnv;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BridgeTest {

    private SuikaEnv newEnv() {
        return new SuikaEnv(ObservationMode.STATE,
                new ActionSpace.Discrete(32), RewardConfig.defaultConfig());
    }

    @Test
    void observationCodecRoundtrip() {
        float[] obs = {0.0f, 1.5f, -2.25f, 3.125f, 42.0f};
        byte[] wire = ObservationCodec.encode(obs);
        float[] back = ObservationCodec.decode(wire);
        assertArrayEquals(obs, back, 0.0f);
    }

    @Test
    void observationCodecActionRoundtrip() {
        double[] action = {0.75, -0.5};
        double[] back = ObservationCodec.decodeAction(ObservationCodec.encodeAction(action));
        assertEquals(action.length, back.length);
        assertEquals(0.75, back[0], 1e-6);
        assertEquals(-0.5, back[1], 1e-6);
    }

    @Test
    void observationCodecRejectsCorruptBuffer() {
        byte[] bad = {127, 0, 0, 0}; // declares length but carries no payload
        assertThrows(IllegalArgumentException.class, () -> ObservationCodec.decode(bad));
    }

    @Test
    void inProcessTransportRequiresConnect() {
        InProcessTransport t = InProcessTransport.constantCentre();
        assertFalse(t.isConnected());
        assertThrows(IllegalStateException.class, () -> t.requestAction(new float[]{0f}));
        t.connect();
        assertTrue(t.isConnected());
        assertArrayEquals(new double[]{0.0}, t.requestAction(new float[]{0f}));
        t.close();
        assertFalse(t.isConnected());
    }

    @Test
    void bridgeConfigFactories() {
        assertEquals(BridgeConfig.Transport.JEP, BridgeConfig.embedded().transport());
        assertEquals(BridgeConfig.Transport.GRPC_SIDECAR, BridgeConfig.sidecar("h", 50051).transport());
        assertEquals(50051, BridgeConfig.sidecar("h", 50051).port());
        assertEquals(BridgeConfig.Transport.DJL_ONNX, BridgeConfig.onnx("policy.onnx").transport());
        assertEquals("policy.onnx", BridgeConfig.onnx("policy.onnx").modelPath());
    }

    @Test
    void gymBridgeResetAndStep() {
        GymBridge gym = new GymBridge(newEnv());
        float[] obs0 = gym.reset(42L);
        assertEquals(gym.observationSize(), obs0.length);

        GymBridge.Transition t = gym.step(0.0);
        assertNotNull(t.observation());
        assertEquals(gym.observationSize(), t.observation().length);
        assertTrue(Double.isFinite(t.reward()));
    }

    @Test
    void gymBridgeReachesTerminalState() {
        GymBridge gym = new GymBridge(newEnv());
        gym.reset(7L);
        boolean done = false;
        for (int i = 0; i < 500 && !done; i++) {
            done = gym.step(0.5).done(); // pile in one column → eventually game over
        }
        assertTrue(done, "Repeated same-column drops should terminate the episode");
    }

    @Test
    void pettingZooSameSeedSameStart() {
        PettingZooBridge pz = new PettingZooBridge(newEnv(), newEnv());
        Map<String, float[]> obs = pz.reset(99L);
        assertArrayEquals(obs.get(PettingZooBridge.AGENT_A), obs.get(PettingZooBridge.AGENT_B),
                "Both racers start from an identical seeded board");

        Map<String, GymBridge.Transition> step = pz.step(0.0, 0.0);
        assertEquals(2, step.size());
        assertTrue(step.containsKey(PettingZooBridge.AGENT_A));
        assertTrue(step.containsKey(PettingZooBridge.AGENT_B));
    }

    @Test
    void onnxRunnerDeployPath() {
        OnnxPolicyRunner runner = new OnnxPolicyRunner.StubOnnxPolicyRunner(32);
        assertFalse(runner.isLoaded());
        assertThrows(IllegalStateException.class, () -> runner.run(new float[]{0f}));

        runner.load(BridgeConfig.onnx("policy.onnx"));
        assertTrue(runner.isLoaded());

        OnnxPolicyRunner.Output out = runner.run(new float[]{0f});
        assertEquals(32, out.policyLogits().length);
        assertEquals(0, out.argmaxAction());
        runner.close();
        assertFalse(runner.isLoaded());
    }
}
