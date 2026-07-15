package dev.suika.ai;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Durable leaderboard + run-history under {@code ~/.suikai/}.
 * Round-trips via {@link Leaderboard#toJsonLines()} / {@link #fromJsonLines}.
 */
public final class RunHistoryStore {

    private RunHistoryStore() {}

    public static Path root() {
        return Path.of(System.getProperty("user.home"), ".suikai");
    }

    public static Path leaderboardFile() { return root().resolve("leaderboard.jsonl"); }
    public static Path runsDir() { return root().resolve("runs"); }
    public static Path replaysDir() { return root().resolve("replays"); }
    public static Path rewardsDir() { return root().resolve("rewards"); }

    public static Leaderboard loadLeaderboard() {
        Path f = leaderboardFile();
        if (!Files.isRegularFile(f)) return new Leaderboard();
        try {
            return Leaderboard.fromJsonLines(Files.readAllLines(f, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return new Leaderboard();
        }
    }

    public static void saveLeaderboard(Leaderboard board) throws Exception {
        Files.createDirectories(root());
        Files.write(leaderboardFile(), board.toJsonLines(), StandardCharsets.UTF_8);
    }

    public static void appendRunSummary(String name, String body) throws Exception {
        Files.createDirectories(runsDir());
        String safe = name == null ? "run" : name.replaceAll("[^a-zA-Z0-9_-]", "_");
        Path out = runsDir().resolve(safe + "-" + System.currentTimeMillis() + ".txt");
        Files.writeString(out, body, StandardCharsets.UTF_8);
    }

    public static List<Path> listRecentRuns(int max) throws Exception {
        if (!Files.isDirectory(runsDir())) return List.of();
        List<Path> out = new ArrayList<>();
        try (var stream = Files.list(runsDir())) {
            stream.filter(p -> p.getFileName().toString().endsWith(".txt"))
                    .sorted((a, b) -> Long.compare(b.toFile().lastModified(), a.toFile().lastModified()))
                    .limit(Math.max(1, max))
                    .forEach(out::add);
        }
        return out;
    }
}
