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
}
