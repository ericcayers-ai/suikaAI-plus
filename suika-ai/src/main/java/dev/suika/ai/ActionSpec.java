package dev.suika.ai;

/** Describes the action space to an AgentPlugin. */
public record ActionSpec(
        boolean discrete,
        int     bins,
        double  continuousMin,
        double  continuousMax
) {
    public static ActionSpec discrete(int bins) {
        return new ActionSpec(true, bins, 0, 0);
    }

    public static ActionSpec continuous(double min, double max) {
        return new ActionSpec(false, 0, min, max);
    }

    /** Convert an action object to a drop x coordinate. */
    public double toDropX(Object action, double xMin, double xMax) {
        if (discrete) {
            int a = ((Number) action).intValue();
            a = Math.max(0, Math.min(bins - 1, a));
            return xMin + (a / (double) (bins - 1)) * (xMax - xMin);
        }
        double a = ((Number) action).doubleValue();
        double t = (a + 1.0) / 2.0;
        return xMin + Math.max(0.0, Math.min(1.0, t)) * (xMax - xMin);
    }
}
