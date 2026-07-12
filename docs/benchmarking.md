# Benchmarking the agents

This project ships a headless score benchmark so you can measure whether an agent change
helped or hurt *strength*, independent of the UI. It plays each technique over a fixed set
of seeds and reports the mean final score.

## Running it

The bench lives in the test/dev harness (`dev.suika.game.Bench`). The quickest path:

```bash
# Build the AI jar the bench evaluates against
./gradlew :suika-ai:jar :suika-game:compileJava

# Then run Bench on the assembled runtime classpath with, e.g.:
#   -Dsteps=500      max drops per game
#   -Drollouts=80    MCTS/ensemble rollout budget
#   -Deps=3          episodes averaged per seed (cuts stochastic-agent variance)
#   -Donly=MCTS,Greedy   restrict to named techniques
```

Seeds are fixed (`{1, 42, 137, 999, 31415}`) so runs are comparable. `GameCore(seed)`
reproduces a game exactly, but agents that use their own RNG (MCTS rollouts) are
stochastic — average several episodes per seed (`-Deps`) before trusting a small delta.

## What "good" looks like

A single **watermelon** (tier 11) is the top of the merge chain; building one banks the
whole chain beneath it, so a score past ~1000 means the agent is sustaining merges rather
than stalemating early. The strongest planners can occasionally reach **two watermelons**
on a lucky seed at a high rollout budget.

## Representative results (v0.17.1)

Two families of technique behave very differently, and it's worth being explicit about why.

### Simulate-and-pick (planning) techniques

These evaluate candidate drops against the real physics (`GameCore.dropAndSettle`) using the
shared survival-aware [`BoardEval`](../suika-ai/src/main/java/dev/suika/ai/BoardEval.java).
With the v0.17.1 MCTS search fix (min-max value normalization + one-ply root seeding) and
the `BoardEval` tuning, they clear — or sit right at — 1000, and climb further at higher
rollout budgets:

| Technique | Mean | Notes |
|---|---:|---|
| AlphaZero (surrogate) | ~1025 | search + heuristic guidance |
| Return-Conditioned + MCTS Verify | ~1015 | proposal sanity-checked by search |
| Greedy One-Ply | ~900–1000 | exact one-step evaluation; deterministic |
| MCTS | ~950–1100 | stochastic; strengthens with more rollouts |
| Adaptive Voting Committee | ~800–900 | MCTS + Greedy + Heuristic, trust-weighted |
| MCTS + Greedy Tiebreak | ~800–870 | |
| Bandit Meta-Controller | ~650–750 | explores weak arms early |
| Heuristic | ~670–745 | no-simulation scripted baseline / rollout policy |

Numbers are at `rollouts=80–100`; the in-app quality presets can go much higher, which
lifts the search techniques further.

### Learned reactive nets

Behavioral Cloning, DAgger, DQN, Neuroevolution, CMA-ES, PBT (and the Python-side PPO /
Decision Transformer) all drive a small MLP
(`584 → 64 → 32`, [`MlpPolicy`](../suika-ai/src/main/java/dev/suika/ai/MlpPolicy.java))
that maps the state vector **directly** to a drop column, with **no lookahead**:

| Technique | Mean (headless, fixed training budget) |
|---|---:|
| PBT | ~650 |
| Decision Transformer | ~485 |
| Behavioral Cloning | ~440 |
| DQN | ~440 |
| Neuroevolution (GA) | ~440 |
| DAgger | ~380 |
| CMA-ES | ~380 |

These are genuinely **modestly competent** — they merge and survive rather than flailing —
but they do not match the planners, and this is a property of the approach, not a bug:

- **No lookahead.** A planner *simulates* every column before choosing; a reactive net must
  predict which column the physics will reward from the pre-drop state alone. Imitating a
  one-ply planner therefore has a low ceiling — BC of Greedy plateaus around 440 regardless
  of how much data or training it gets.
- **Representation + capacity.** The observation is a 584-dim ID-sorted fruit list that is
  mostly zero early game; a 64-wide MLP has limited room to carve it up.
- **Training budget.** The evolution/RL techniques are still climbing at the small
  generation/episode counts used here; in-app they train open-endedly and improve further.

**Pushing reactive nets past 1000** would take architectural work rather than tuning: a
compact column-profile observation, a larger net, an Adam-class optimizer for the imitation
trainers, or a net-guided-search hybrid (which is exactly what AlphaZero and the MCTS+Net
ensemble already are — and why they score in the planner band). These are tracked as future
work; see [`ROADMAP-NEXT.md`](../ROADMAP-NEXT.md).

## Using the bench in a PR

For any change that touches an agent or `BoardEval`, include before/after bench numbers in
the PR description (`-Deps=3` for the stochastic ones). A strength regression on the planners
should be caught here before review.
