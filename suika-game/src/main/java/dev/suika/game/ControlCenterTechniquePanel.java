package dev.suika.game;

import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Rectangle;
import dev.suika.ai.HyperparamSchema;

import java.util.ArrayList;
import java.util.List;

/**
 * Technique-specific hotswap / drawer host: shared preset / speed / parallelism
 * plus a {@link TechniqueConfigPanel} for schema-driven knobs.
 */
public final class ControlCenterTechniquePanel {

    public final Rectangle presetCtrl = new Rectangle();
    public final Rectangle speedCtrl = new Rectangle();
    public final Rectangle paraCtrl = new Rectangle();
    public final TechniqueConfigPanel schemaPanel = new TechniqueConfigPanel();
    public final Rectangle closeBtn = new Rectangle();

    private TechniqueConfigPanel.Binding binding;
    private List<HyperparamSchema> schemas = List.of();

    public void bind(PlaygroundConfig cfg) {
        binding = TechniqueConfigPanel.playgroundBinding(cfg);
    }

    public void layout(float originX, float originYTop, float labelW, float valueW,
                       PlaygroundConfig cfg, boolean researcher) {
        bind(cfg);
        schemas = new ArrayList<>(TechniqueConfigPanel.schemasFor(cfg.technique));
        if (!researcher) {
            // Explorer: keep the panel lean — primary param + evolution launch only.
            schemas.removeIf(s -> "exploration_c".equals(s.key())
                    || "rollout_depth".equals(s.key())
                    || "elite_count".equals(s.key()));
        }
        float y = originYTop;
        presetCtrl.set(originX + labelW, y - 38f, valueW, 38f);
        y -= 54f;
        speedCtrl.set(originX + labelW, y - 38f, valueW, 38f);
        y -= 54f;
        paraCtrl.set(originX + labelW, y - 38f, valueW, 38f);
        y -= 54f;
        schemaPanel.layout(originX, y, labelW, valueW, schemas);
    }

    public void drawShapes(ShapeRenderer s, PlaygroundConfig cfg, float mx, float my, UiFocus focus) {
        boolean para = cfg.technique.parallel;
        Ui.cycler(s, presetCtrl, presetCtrl.contains(mx, my), true);
        Ui.cycler(s, speedCtrl, speedCtrl.contains(mx, my), true);
        Ui.cycler(s, paraCtrl, paraCtrl.contains(mx, my), para);
        if (binding != null) schemaPanel.drawShapes(s, binding, mx, my, focus);
        if (focus != null) {
            if (focus.isFocused(presetCtrl)) Ui.focusOutline(s, presetCtrl.x, presetCtrl.y, presetCtrl.width, presetCtrl.height);
            if (focus.isFocused(speedCtrl)) Ui.focusOutline(s, speedCtrl.x, speedCtrl.y, speedCtrl.width, speedCtrl.height);
            if (focus.isFocused(paraCtrl)) Ui.focusOutline(s, paraCtrl.x, paraCtrl.y, paraCtrl.width, paraCtrl.height);
        }
    }

    public void drawText(SpriteBatch b, BitmapFont label, BitmapFont value, PlaygroundConfig cfg) {
        Ui.text(b, label, "Preset", presetCtrl.x - 200f, presetCtrl.y + 26f, Theme.TEXT);
        Ui.cyclerLabel(b, value, presetCtrl, cfg.preset.cyclerLabel(), true);
        Ui.text(b, label, "Speed", speedCtrl.x - 200f, speedCtrl.y + 26f, Theme.TEXT);
        Ui.cyclerLabel(b, value, speedCtrl, cfg.speedLabel(), true);
        boolean para = cfg.technique.parallel;
        Ui.text(b, label, "Parallelism", paraCtrl.x - 200f, paraCtrl.y + 26f,
                para ? Theme.TEXT : Theme.TEXT_FAINT);
        Ui.cyclerLabel(b, value, paraCtrl, para ? cfg.parallelismLabel() : "n/a", para);
        if (binding != null) schemaPanel.drawText(b, label, value, binding);
    }

    /** Returns true if a control handled the click. */
    public boolean click(float x, float y, PlaygroundConfig cfg) {
        if (presetCtrl.contains(x, y)) {
            if (!PresetCalibration.calibrated()) return true; // consumed; caller may toast
            HardwarePresets[] presets = HardwarePresets.values();
            int idx = Math.floorMod(cfg.preset.ordinal() + dir(x, presetCtrl), presets.length);
            presets[idx].applyTo(cfg);
            return true;
        }
        if (speedCtrl.contains(x, y)) {
            cfg.speedIndex = Math.floorMod(cfg.speedIndex + dir(x, speedCtrl), PlaygroundConfig.SPEEDS.length);
            return true;
        }
        if (paraCtrl.contains(x, y) && cfg.technique.parallel) {
            int cores = Runtime.getRuntime().availableProcessors();
            cfg.parallelism = Math.max(0, Math.min(cores, cfg.parallelism + dir(x, paraCtrl)));
            return true;
        }
        return binding != null && schemaPanel.click(x, y, binding);
    }

    public void registerFocus(UiFocus focus) {
        focus.add(presetCtrl);
        focus.add(speedCtrl);
        focus.add(paraCtrl);
        schemaPanel.registerFocus(focus);
    }

    public float schemaHeight() {
        return schemaPanel.heightFor(schemas.size());
    }

    private static int dir(float x, Rectangle r) {
        return x < r.x + r.width / 2f ? -1 : +1;
    }
}
