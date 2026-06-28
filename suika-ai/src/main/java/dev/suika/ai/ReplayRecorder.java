package dev.suika.ai;

import dev.suika.core.GameCore;
import dev.suika.core.GameState;
import dev.suika.core.StepResult;
import dev.suika.env.StateObservationEncoder;

import java.util.ArrayList;
import java.util.List;

/**
 * Records human (or expert) gameplay into a {@link DemoDataset}.
 *
 * <p>Wraps a {@link GameCore} and intercepts each drop to capture
 * {@code (observation, action)} pairs with their rewards. The collected
 * dataset is then passed to {@link BehavioralCloningTrainer} or
 * {@link DAggerTrainer}.
 */
public final class ReplayRecorder {

    private final GameCore              core;
    private final StateObservationEncoder encoder = new StateObservationEncoder();
    private final int                   actionBins;
    private final List<Demonstration>   buffer  = new ArrayList<>();

    private GameState lastState;

    public ReplayRecorder(GameCore core, int actionBins) {
        this.core       = core;
        this.actionBins = actionBins;
        this.lastState  = core.getState();
    }

    /**
     * Record a human drop at position {@code dropX} and step the game.
     * @return the step result (for the UI to show)
     */
    public StepResult recordDrop(double dropX) {
        float[] obs = encoder.encode(lastState);
        int action = xToAction(dropX);

        StepResult result = core.dropAndSettle(dropX);
        double reward = result.mergesThisStep().stream()
                .mapToLong(dev.suika.core.MergeEvent::scoreAwarded).sum();

        buffer.add(new Demonstration(obs, action, reward, result.terminated()));
        lastState = result.observation();
        return result;
    }

    /** Flush the recorded demonstrations into the provided dataset. */
    public void flushInto(DemoDataset dataset) {
        dataset.addAll(buffer);
        buffer.clear();
    }

    public int recordedCount() { return buffer.size(); }
    public GameState currentState() { return lastState; }
    public boolean isGameOver()   { return core.isGameOver(); }

    private int xToAction(double x) {
        double t = (x - dev.suika.core.PhysicsConfig.DROP_X_MIN)
                / (dev.suika.core.PhysicsConfig.DROP_X_MAX - dev.suika.core.PhysicsConfig.DROP_X_MIN);
        return (int) Math.round(Math.max(0, Math.min(actionBins - 1, t * (actionBins - 1))));
    }
}
