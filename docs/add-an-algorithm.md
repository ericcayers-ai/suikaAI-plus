# Add an Algorithm in 30 Minutes

This guide adds a new AI agent to the Suika sandbox end-to-end:
registered in the plugin system, configurable in the UI, and benchmarkable.

## Step 1 — Create the agent class (~10 min)

Extend `ByoaTemplate` (or implement `AgentPlugin` directly):

```java
package com.example;

import dev.suika.ai.*;
import dev.suika.core.GameState;

public final class MyAgent extends ByoaTemplate {

    @Override public String id()          { return "my-agent"; }
    @Override public String displayName() { return "My Agent"; }

    @Override
    public Object selectAction(GameState state, ActionSpec spec) {
        // Return an int in [0, spec.bins()) for discrete action spaces.
        // Use ActionSpec.toDropX(action, dropXMin, dropXMax) to convert to physics x.
        int bins = spec.bins();
        return (int)(Math.random() * bins);   // replace with your logic
    }

    @Override
    public void initialize(AgentConfig config) {
        super.initialize(config);
        // Read hyperparams: int n = hp("n_steps", 10);
    }
}
```

## Step 2 — Register via ServiceLoader (~2 min)

Create (or append to) `src/main/resources/META-INF/services/dev.suika.ai.AgentPlugin`:

```
com.example.MyAgent
```

Your agent will now appear in `PluginRegistry.get().agents()` and the algorithm picker.

## Step 3 — Declare hyperparameters (~5 min, optional)

In `suika-app`, add a schema factory:

```java
public static List<HyperparamSchema> forMyAgent() {
    return List.of(
        HyperparamSchema.intParam("n_steps", "Steps", 10, 1, 100, "Rollout horizon.")
    );
}
```

Pair it with an `AgentPreset` entry so Explorer mode users see a friendly description.

## Step 4 — Run the benchmark (~5 min)

```java
BenchmarkSuite suite = new BenchmarkSuite();           // 5 canonical seeds × 3 episodes
LeaderboardEntry entry = suite.evaluate(new MyAgent());
System.out.println("Mean score: " + entry.meanScore());

Leaderboard board = new Leaderboard();
board.submit(entry);
board.toJsonLines().forEach(System.out::println);
```

## Step 5 — Write a test (~8 min)

```java
@Test
void myAgentScoresPositive() {
    BenchmarkSuite suite = new BenchmarkSuite(List.of(42L), 1, 50);
    LeaderboardEntry e = suite.evaluate(new MyAgent());
    assertTrue(e.meanScore() >= 0);
}
```

## What you did NOT have to touch

- `GameCore`, `SuikaEnv`, `PhysicsConfig`, `FruitTier` — the engine is unchanged.
- Any existing agent — plugins are fully isolated.
- The dashboard — `EvolutionMetricsLogger` works with any trainer that implements `TrainerPlugin`.
