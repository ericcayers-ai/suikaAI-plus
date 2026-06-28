package dev.suika.ai;

/**
 * Hyperparameter configuration for AlphaZero-style training (ROADMAP §IV.2).
 * The MCTS is guided by a policy-value network; this config parameterises the search.
 */
public record AlphaZeroConfig(
        int    rolloutsPerMove,
        double explorationC,
        double dirichletAlpha,
        double dirichletWeight,
        int    actionBins,
        int    selfPlayGamesPerIter,
        int    trainingBatchSize,
        double learningRate
) {
    public static AlphaZeroConfig defaults() {
        return new AlphaZeroConfig(
                50,    // rollouts per move (increase to taste)
                1.4,   // UCB exploration constant
                0.3,   // Dirichlet noise alpha
                0.25,  // noise weight (mix with policy prior)
                32,    // discrete drop positions
                10,    // self-play games per iteration
                256,   // training batch size
                1e-3   // learning rate
        );
    }
}
