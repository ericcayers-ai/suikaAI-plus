package dev.suika.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * In-memory dataset of {@link Demonstration}s collected by the replay recorder
 * or by the DAgger labelling loop (ROADMAP §IV.6).
 *
 * <p>Provides mini-batch sampling for Behavioral Cloning training.
 */
public final class DemoDataset {

    private final List<Demonstration> data = new ArrayList<>();
    private final Random              rng;

    public DemoDataset()            { this.rng = new Random(); }
    public DemoDataset(long seed)   { this.rng = new Random(seed); }

    public void add(Demonstration d) { data.add(d); }
    public void addAll(List<Demonstration> demos) { data.addAll(demos); }

    public int size() { return data.size(); }
    public List<Demonstration> all() { return Collections.unmodifiableList(data); }

    /**
     * Sample a random mini-batch of {@code batchSize} demonstrations (with replacement).
     */
    public List<Demonstration> sample(int batchSize) {
        if (data.isEmpty()) return List.of();
        List<Demonstration> batch = new ArrayList<>(batchSize);
        for (int i = 0; i < batchSize; i++) {
            batch.add(data.get(rng.nextInt(data.size())));
        }
        return batch;
    }

    /** Shuffle in-place for epoch-based training. */
    public void shuffle() { Collections.shuffle(data, rng); }
}
