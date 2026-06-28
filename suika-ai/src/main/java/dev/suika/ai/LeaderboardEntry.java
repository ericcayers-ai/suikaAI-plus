package dev.suika.ai;

import java.util.List;

/**
 * One row in the community benchmark leaderboard (ROADMAP §XII).
 *
 * @param agentId     unique plugin identifier
 * @param displayName human-readable name
 * @param meanScore   mean game score across all benchmark episodes
 * @param stdDev      standard deviation of scores
 * @param episodeCount total episodes used for this entry
 * @param seeds       standardized seeds used for evaluation
 * @param epochMs     wall-clock time of submission (ms since epoch)
 */
public record LeaderboardEntry(
        String      agentId,
        String      displayName,
        double      meanScore,
        double      stdDev,
        int         episodeCount,
        List<Long>  seeds,
        long        epochMs
) implements Comparable<LeaderboardEntry> {

    @Override
    public int compareTo(LeaderboardEntry other) {
        return Double.compare(other.meanScore, this.meanScore);
    }

    /** One-line JSON representation for append-only leaderboard files. */
    public String toJsonLine() {
        return String.format(
                "{\"agentId\":\"%s\",\"displayName\":\"%s\",\"meanScore\":%.2f," +
                "\"stdDev\":%.2f,\"episodeCount\":%d,\"epochMs\":%d}",
                agentId, displayName, meanScore, stdDev, episodeCount, epochMs);
    }
}
