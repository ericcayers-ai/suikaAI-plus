package dev.suika.env;

/** Describes the agent's action space. */
public sealed interface ActionSpace {

    /**
     * Discrete N-bin action space.
     * Action {@code a ∈ [0, bins)} maps linearly to a drop x.
     */
    record Discrete(int bins) implements ActionSpace {
        public double toDropX(int action, double xMin, double xMax) {
            return xMin + (action / (double) (bins - 1)) * (xMax - xMin);
        }
    }

    /**
     * Continuous action space.
     * Action {@code a ∈ [-1.0, 1.0]} maps linearly to a drop x.
     */
    record Continuous() implements ActionSpace {
        public double toDropX(double action, double xMin, double xMax) {
            double t = (action + 1.0) / 2.0;
            return xMin + t * (xMax - xMin);
        }
    }
}
