package dev.suika.game;

import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Rectangle;
import dev.suika.ai.HyperparamSchema;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Reusable technique-configuration panel driven by {@link HyperparamSchema}.
 * Playground drawer and Control Center hotswap can host this panel; workflow
 * redesign owns full migration — this API is ready to plug in.
 *
 * <p>Usage:
 * <ol>
 *   <li>{@link #layout(float, float, float, float, List)} once when opening</li>
 *   <li>{@link #drawShapes}/{@link #drawText} each frame</li>
 *   <li>{@link #click(float, float, Binding)} on pointer down</li>
 * </ol>
 */
public final class TechniqueConfigPanel {

    /** Reads / mutates live values for one schema row. */
    public interface Binding {
        String display(HyperparamSchema schema);
        boolean enabled(HyperparamSchema schema);
        void cycle(HyperparamSchema schema, int dir);
        default boolean isToggle(HyperparamSchema schema) {
            return schema.type() == HyperparamSchema.Type.BOOLEAN;
        }
        default void toggle(HyperparamSchema schema) { cycle(schema, +1); }
    }

    public static final class Row {
        public final HyperparamSchema schema;
        public final Rectangle area = new Rectangle();
        public Row(HyperparamSchema schema) { this.schema = schema; }
    }

    private final List<Row> rows = new ArrayList<>();
    private float labelX, valueX, rowH = 38f, step = 54f;

    public List<Row> rows() { return rows; }

    public void layout(float originX, float originYTop, float labelWidth, float valueWidth,
                       List<HyperparamSchema> schemas) {
        rows.clear();
        labelX = originX;
        valueX = originX + labelWidth;
        float y = originYTop;
        for (HyperparamSchema s : schemas) {
            Row r = new Row(s);
            r.area.set(valueX, y - rowH, valueWidth, Math.max(rowH, Theme.MIN_TARGET * 0.75f));
            Ui.ensureMinTarget(r.area);
            rows.add(r);
            y -= step;
        }
    }

    public float heightFor(int count) {
        return count <= 0 ? 0f : (count - 1) * step + rowH;
    }

    public void drawShapes(ShapeRenderer s, Binding binding, float mx, float my, UiFocus focus) {
        for (Row r : rows) {
            boolean en = binding.enabled(r.schema);
            boolean hov = en && r.area.contains(mx, my);
            if (binding.isToggle(r.schema)) {
                Ui.toggle(s, r.area.x, r.area.y + 4f, 70f, r.area.height - 8f,
                        "true".equalsIgnoreCase(binding.display(r.schema))
                                || "on".equalsIgnoreCase(binding.display(r.schema)));
            } else {
                Ui.cycler(s, r.area, hov, en);
            }
            if (focus != null && focus.isFocused(r.area))
                Ui.focusOutline(s, r.area.x, r.area.y, r.area.width, r.area.height);
        }
    }

    public void drawText(SpriteBatch b, BitmapFont labelFont, BitmapFont valueFont,
                         Binding binding) {
        for (Row r : rows) {
            boolean en = binding.enabled(r.schema);
            Ui.text(b, labelFont, r.schema.displayName(), labelX,
                    r.area.y + r.area.height / 2f + 6f, en ? Theme.TEXT_DIM : Theme.TEXT_FAINT);
            if (!binding.isToggle(r.schema))
                Ui.cyclerLabel(b, valueFont, r.area, binding.display(r.schema), en);
        }
    }

    /**
     * Hit-test. Returns true when a row handled the click.
     * Left half of a cycler = −1, right half = +1.
     */
    public boolean click(float x, float y, Binding binding) {
        for (Row r : rows) {
            if (!r.area.contains(x, y) || !binding.enabled(r.schema)) continue;
            if (binding.isToggle(r.schema)) {
                binding.toggle(r.schema);
            } else {
                int dir = x < r.area.x + r.area.width / 2f ? -1 : +1;
                binding.cycle(r.schema, dir);
            }
            return true;
        }
        return false;
    }

    public void registerFocus(UiFocus focus) {
        for (Row r : rows) focus.add(r.area);
    }

    /**
     * Default binding against a {@link PlaygroundConfig} for the primary param /
     * evolution launch keys. Ensemble / selection knobs stay on the screen until
     * workflow-redesign migrates them.
     */
    public static Binding playgroundBinding(PlaygroundConfig cfg) {
        return new Binding() {
            @Override public String display(HyperparamSchema schema) {
                return switch (schema.key()) {
                    case "rollouts" -> Integer.toString(cfg.rollouts);
                    case "population_size" -> Integer.toString(cfg.populationSize);
                    case "target_return" -> Integer.toString((int) cfg.targetReturn);
                    case "learning_rate" -> String.format("%.0e", cfg.learningRate);
                    case "mutation_sigma" -> String.format("%.2f", cfg.mutationSigma);
                    case "episodes_per_eval", "sims_per_gen" -> Integer.toString(cfg.simsPerGen());
                    case "ghost_cull_gens" -> cfg.ghostCullGens() + " gens";
                    case "elite_views" -> cfg.eliteViewCount() + "x";
                    case "exploration_c" -> String.format("%.1f",
                            HyperparamSchema.UCB_C[Math.min(cfg.ucbCIndex, HyperparamSchema.UCB_C.length - 1)]);
                    default -> TechniqueHyperparams.paramValue(cfg);
                };
            }

            @Override public boolean enabled(HyperparamSchema schema) {
                return switch (schema.key()) {
                    case "rollouts" -> TechniqueHyperparams.ROLLOUT_PARAM_TECHS.contains(cfg.technique);
                    case "population_size" -> switch (cfg.technique) {
                        case NEUROEVO, CMA_ES, PBT -> true;
                        default -> false;
                    };
                    case "target_return" -> cfg.technique == AiTechnique.DECISION_TRANSFORMER
                            || cfg.technique == AiTechnique.ENS_RTG_VERIFIED;
                    case "learning_rate" -> switch (cfg.technique) {
                        case DAGGER, BC, DQN -> true;
                        default -> false;
                    };
                    case "sims_per_gen", "episodes_per_eval", "ghost_cull_gens", "elite_views",
                         "mutation_sigma" -> TechniqueHyperparams.evolutionApplicable(cfg.technique);
                    case "exploration_c", "rollout_depth", "elite_count" ->
                            TechniqueHyperparams.ROLLOUT_PARAM_TECHS.contains(cfg.technique)
                                    || TechniqueHyperparams.evolutionApplicable(cfg.technique);
                    default -> TechniqueHyperparams.paramApplicable(cfg.technique);
                };
            }

            @Override public void cycle(HyperparamSchema schema, int dir) {
                switch (schema.key()) {
                    case "rollouts", "population_size", "target_return", "learning_rate" ->
                            TechniqueHyperparams.cycleParam(cfg, dir);
                    case "sims_per_gen", "episodes_per_eval" ->
                            cfg.simsPerGenIndex = Math.floorMod(
                                    cfg.simsPerGenIndex + dir, PlaygroundConfig.SIMS_PER_GEN_OPTIONS.length);
                    case "ghost_cull_gens" ->
                            cfg.ghostCullIndex = Math.floorMod(
                                    cfg.ghostCullIndex + dir, PlaygroundConfig.GHOST_CULL_OPTIONS.length);
                    case "elite_views" ->
                            cfg.eliteViewIndex = Math.floorMod(
                                    cfg.eliteViewIndex + dir, PlaygroundConfig.ELITE_VIEW_OPTIONS.length);
                    case "mutation_sigma" -> {
                        cfg.mutationSigmaIndex = Math.floorMod(
                                cfg.mutationSigmaIndex + dir, PlaygroundConfig.MUTATION_SIGMA_OPTIONS.length);
                        cfg.mutationSigma = PlaygroundConfig.MUTATION_SIGMA_OPTIONS[cfg.mutationSigmaIndex];
                    }
                    case "exploration_c" ->
                            cfg.ucbCIndex = Math.floorMod(cfg.ucbCIndex + dir, PlaygroundConfig.UCB_C_OPTIONS.length);
                    default -> TechniqueHyperparams.cycleParam(cfg, dir);
                }
            }
        };
    }

    /** Builds the schema list a panel should show for a technique (primary + launch). */
    public static List<HyperparamSchema> schemasFor(AiTechnique t) {
        List<HyperparamSchema> out = new ArrayList<>(HyperparamSchema.forTechniqueId(t.id));
        if (TechniqueHyperparams.evolutionApplicable(t))
            out.addAll(HyperparamSchema.forEvolutionLaunch());
        return out;
    }

    /** Map technique id → schema via a custom resolver (plugins / Researcher mode). */
    public static List<HyperparamSchema> schemasFor(
            AiTechnique t, Function<String, List<HyperparamSchema>> resolver) {
        List<HyperparamSchema> out = new ArrayList<>(resolver.apply(t.id));
        if (TechniqueHyperparams.evolutionApplicable(t))
            out.addAll(HyperparamSchema.forEvolutionLaunch());
        return out;
    }
}
