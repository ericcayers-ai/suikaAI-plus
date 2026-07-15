package dev.suika.game;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Searchable / filterable technique matrix for {@link AiPlaygroundScreen}.
 * Explorer mode shows the curated Playground matrix; Researcher mode adds
 * {@link dev.suika.ai.PluginRegistry} agent-plugin hooks as informational rows
 * (full plugin UI depth is owned by research-surfaces).
 */
public final class TechniqueCatalog {

    public enum Mode { EXPLORER, RESEARCHER }

    /** Family / kind filter for the matrix list. */
    public enum Filter {
        ALL("All"),
        PLANNING("Planning"),
        EVOLUTION("Evolution"),
        IMITATION("Imitation"),
        DEEP_RL("Deep RL"),
        PYTHON("Python / Offline"),
        ENSEMBLES("Ensembles"),
        BASELINE("Baseline");

        public final String label;
        Filter(String label) { this.label = label; }
    }

    /** One list row: either a curated technique, an ensemble header, or a plugin stub. */
    public sealed interface Row permits TechRow, EnsembleHeader, PluginRow {}

    public record TechRow(AiTechnique technique) implements Row {}
    public record EnsembleHeader(int count) implements Row {}
    public record PluginRow(String id, String displayName, String blurb) implements Row {}

    public Mode mode = Mode.EXPLORER;
    public Filter filter = Filter.ALL;
    /** Case-insensitive substring match against technique display / id / category. */
    public String query = "";
    public boolean ensemblesExpanded = false;

    public void cycleMode(int dir) {
        Mode[] vals = Mode.values();
        mode = vals[Math.floorMod(mode.ordinal() + dir, vals.length)];
    }

    public void cycleFilter(int dir) {
        Filter[] vals = Filter.values();
        filter = vals[Math.floorMod(filter.ordinal() + dir, vals.length)];
    }

    public void appendQueryChar(char c) {
        if (Character.isLetterOrDigit(c) || c == ' ' || c == '-' || c == '_') {
            query = (query + c).toLowerCase(Locale.ROOT);
            if (query.length() > 32) query = query.substring(0, 32);
        }
    }

    public void backspaceQuery() {
        if (!query.isEmpty()) query = query.substring(0, query.length() - 1);
    }

    public void clearQuery() { query = ""; }

    public String modeLabel() {
        return mode == Mode.EXPLORER ? "Explorer" : "Researcher";
    }

    public String filterLabel() { return filter.label; }

    public String queryLabel() {
        return query.isEmpty() ? "(type to search)" : query + "▌";
    }

    /** Builds the visible list for the current mode / filter / query. */
    public List<Row> buildRows() {
        List<AiTechnique> ensembles = new ArrayList<>();
        List<AiTechnique> others = new ArrayList<>();
        for (AiTechnique t : AiTechnique.values()) {
            if (!matches(t)) continue;
            if (t.isEnsemble()) ensembles.add(t);
            else others.add(t);
        }
        ensembles.sort((a, b) -> Integer.compare(b.strength, a.strength));
        others.sort((a, b) -> Integer.compare(b.strength, a.strength));

        List<Row> rows = new ArrayList<>();
        if (!ensembles.isEmpty() && (filter == Filter.ALL || filter == Filter.ENSEMBLES
                || (filter != Filter.BASELINE && queryMatchesAny(ensembles)))) {
            rows.add(new EnsembleHeader(ensembles.size()));
            if (ensemblesExpanded || filter == Filter.ENSEMBLES)
                for (AiTechnique t : ensembles) rows.add(new TechRow(t));
        }
        for (AiTechnique t : others) rows.add(new TechRow(t));

        if (mode == Mode.RESEARCHER) {
            for (var plugin : dev.suika.ai.PluginRegistry.get().agents()) {
                if (isCuratedId(plugin.id())) continue;
                if (!query.isEmpty()) {
                    String hay = (plugin.id() + " " + plugin.displayName()).toLowerCase(Locale.ROOT);
                    if (!hay.contains(query)) continue;
                }
                rows.add(new PluginRow(plugin.id(), plugin.displayName(),
                        "Plugin · research-surfaces will deepen this hook"));
            }
        }
        return rows;
    }

    private boolean queryMatchesAny(List<AiTechnique> list) {
        if (query.isEmpty()) return true;
        for (AiTechnique t : list) if (matchesQuery(t)) return true;
        return false;
    }

    private boolean matches(AiTechnique t) {
        if (!matchesFilter(t)) return false;
        return matchesQuery(t);
    }

    private boolean matchesFilter(AiTechnique t) {
        return switch (filter) {
            case ALL -> true;
            case PLANNING -> t.family == AiTechnique.Family.PLANNING && !t.isEnsemble()
                    && !"scripted".equals(t.kind);
            case EVOLUTION -> t.family == AiTechnique.Family.EVOLUTION;
            case IMITATION -> t.family == AiTechnique.Family.IMITATION;
            case DEEP_RL -> t.family == AiTechnique.Family.DEEP_RL;
            case PYTHON -> t.family == AiTechnique.Family.PYTHON;
            case ENSEMBLES -> t.isEnsemble();
            case BASELINE -> "scripted".equals(t.kind) || "heuristic".equals(t.id);
        };
    }

    private boolean matchesQuery(AiTechnique t) {
        if (query.isEmpty()) return true;
        String hay = (t.display + " " + t.id + " " + t.category + " " + t.kind)
                .toLowerCase(Locale.ROOT);
        return hay.contains(query);
    }

    private static boolean isCuratedId(String id) {
        for (AiTechnique t : AiTechnique.values()) if (t.id.equals(id)) return true;
        return false;
    }
}
