package dev.suika.game;

import com.badlogic.gdx.math.Rectangle;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Pure-Java layout / scroll / hyperparam helpers for the UI foundation. */
class UiFoundationTest {

    @Test
    void scrollClampsAndPages() {
        UiScroll s = new UiScroll(1000f, 200f);
        s.wheel(1f);
        assertEquals(Theme.SCROLL_STEP, s.offset, 0.01f);
        s.end();
        assertEquals(800f, s.offset, 0.01f);
        s.home();
        assertEquals(0f, s.offset, 0.01f);
        s.page(+1);
        assertEquals(Theme.SCROLL_PAGE, s.offset, 0.01f);
        assertTrue(s.key(com.badlogic.gdx.Input.Keys.DOWN));
        assertTrue(s.offset > Theme.SCROLL_PAGE);
    }

    @Test
    void focusCyclesTabOrder() {
        UiFocus f = new UiFocus();
        Rectangle a = new Rectangle(0, 0, 40, 40);
        Rectangle b = new Rectangle(0, 50, 40, 40);
        f.add(a); f.add(b);
        f.next();
        assertSame(a, f.current());
        f.next();
        assertSame(b, f.current());
        f.prev();
        assertSame(a, f.current());
    }

    @Test
    void modalOwnsDismiss() {
        UiModal m = new UiModal();
        assertFalse(m.active());
        m.open(UiModal.Kind.HOTSWAP);
        assertTrue(m.ownsInput());
        assertTrue(m.dismiss());
        assertFalse(m.active());
        assertFalse(m.dismiss());
    }

    @Test
    void toastExpires() {
        UiToast t = new UiToast();
        t.show("Saved", UiToast.Tone.SUCCESS, 1f);
        assertTrue(t.visible());
        t.tick(0.6f);
        assertTrue(t.visible());
        t.tick(0.6f);
        assertFalse(t.visible());
    }

    @Test
    void layoutProfilesMatchTheme() {
        assertEquals(Theme.VW, UiLayout.width(UiLayout.Profile.PORTRAIT), 0.01f);
        assertEquals(Theme.VW_L, UiLayout.width(UiLayout.Profile.LANDSCAPE), 0.01f);
        assertTrue(Theme.isLandscapeWindow(1600, 900));
        assertFalse(Theme.isLandscapeWindow(720, 1280));
    }

    @Test
    void ensureMinTargetExpandsSmallHits() {
        Rectangle r = new Rectangle(10, 10, 20, 20);
        Ui.ensureMinTarget(r);
        assertTrue(r.width >= Theme.MIN_TARGET);
        assertTrue(r.height >= Theme.MIN_TARGET);
    }

    @Test
    void techniqueHyperparamsCycleRollouts() {
        PlaygroundConfig cfg = new PlaygroundConfig();
        cfg.selectDefaultsFor(AiTechnique.MCTS);
        int before = cfg.rollouts;
        TechniqueHyperparams.cycleParam(cfg, +1);
        assertNotEquals(before, cfg.rollouts);
        assertEquals("Rollouts", TechniqueHyperparams.paramLabel(AiTechnique.MCTS));
        assertTrue(TechniqueHyperparams.paramApplicable(AiTechnique.MCTS));
        assertFalse(TechniqueHyperparams.paramApplicable(AiTechnique.HEURISTIC));
    }

    @Test
    void techniqueConfigPanelSchemasIncludeEvolutionLaunch() {
        var schemas = TechniqueConfigPanel.schemasFor(AiTechnique.NEUROEVO);
        assertFalse(schemas.isEmpty());
        assertTrue(schemas.stream().anyMatch(s -> s.key().equals("population_size")));
        assertTrue(schemas.stream().anyMatch(s -> s.key().equals("sims_per_gen")));
    }

    @Test
    void hyperparamLaddersSharedWithPlayground() {
        assertSame(dev.suika.ai.HyperparamSchema.SIMS_PER_GEN, PlaygroundConfig.SIMS_PER_GEN_OPTIONS);
        assertSame(dev.suika.ai.HyperparamSchema.ROLLOUTS, TechniqueHyperparams.ROLLOUTS);
        assertSame(dev.suika.ai.HyperparamSchema.POPULATION, TechniqueHyperparams.POP);
    }

    @Test
    void motionTokenHonoursReducedFlag() {
        assertEquals(0f, Theme.motion(0.22f, true), 0.0001f);
        assertEquals(0.22f, Theme.motion(0.22f, false), 0.0001f);
    }

    @Test
    void uiKeysPauseAndDrop() {
        assertTrue(UiKeys.isPause(com.badlogic.gdx.Input.Keys.P));
        assertTrue(UiKeys.dropKeyForHumanBoard(com.badlogic.gdx.Input.Keys.DOWN, true));
        assertFalse(UiKeys.dropKeyForHumanBoard(com.badlogic.gdx.Input.Keys.SPACE, true));
        assertTrue(UiKeys.dropKeyForHumanBoard(com.badlogic.gdx.Input.Keys.SPACE, false));
    }
}
