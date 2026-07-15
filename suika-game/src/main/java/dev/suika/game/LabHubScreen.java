package dev.suika.game;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.ScreenAdapter;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.viewport.FitViewport;
import dev.suika.ai.PluginRegistry;
import dev.suika.ai.RunHistoryStore;
import dev.suika.core.PhysicsGoldenTool;
import dev.suika.core.ReplayLog;
import dev.suika.core.ReplayScrubber;
import dev.suika.dash.DashboardPanelModel;
import dev.suika.dash.DashboardRegistry;
import dev.suika.env.RewardStudioModel;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Research Lab Hub — Reward Studio, Dashboard, bounded Bench, Replay scrub,
 * physics golden tooling, and plugin discovery (Researcher hooks).
 */
public final class LabHubScreen extends ScreenAdapter {

    private enum Tab { REWARD, DASHBOARD, BENCH, REPLAY, PHYSICS, PLUGINS }

    private final SuikaGame game;
    private final OrthographicCamera camera = new OrthographicCamera();
    private final FitViewport viewport;
    private final Vector3 touch = new Vector3();
    private float mx, my;
    private final UiScroll scroll = new UiScroll();
    private final UiToast toast = new UiToast();
    private final UiFocus focus = new UiFocus();

    private Tab tab = Tab.REWARD;
    private final Rectangle[] tabBtns = new Rectangle[Tab.values().length];
    private final Rectangle backBtn = new Rectangle(Theme.VW / 2f - 130, 40, 260, 56);
    private final Rectangle actionBtn = new Rectangle(Theme.VW - 220, 40, 180, 56);
    private final Rectangle action2Btn = new Rectangle(Theme.VW - 420, 40, 180, 56);

    private final RewardStudioModel reward = new RewardStudioModel();
    private int rewardTermIndex = 0;
    private final DashboardPanelModel dash = new DashboardPanelModel(DashboardRegistry.get());
    private final AtomicReference<String> benchStatus = new AtomicReference<>("Idle — tap RUN for a bounded bench");
    private volatile boolean benchRunning = false;
    private ReplayScrubber scrubber;
    private String physicsNote = "";
    private float listScrollY = 0f;

    public LabHubScreen(SuikaGame game) {
        this.game = game;
        viewport = new FitViewport(Theme.VW, Theme.VH, camera);
        camera.position.set(Theme.VW / 2f, Theme.VH / 2f, 0f);
        camera.update();
        float x = 24f;
        for (int i = 0; i < tabBtns.length; i++) {
            tabBtns[i] = new Rectangle(x, Theme.VH - 100, 110, 40);
            x += 118f;
        }
        // Seed a demo replay for scrub UI.
        ReplayLog demo = new ReplayLog(42L);
        for (double d : PhysicsGoldenTool.CANONICAL_DROPS) demo.record(d, 0);
        scrubber = new ReplayScrubber(demo);
        physicsNote = "Canonical golden: score=" + PhysicsGoldenTool.canonical().score();
    }

    @Override
    public void show() {
        focus.clear();
        for (Rectangle r : tabBtns) focus.add(r);
        focus.add(actionBtn); focus.add(action2Btn); focus.add(backBtn);
        focus.ensureStarted();
        Gdx.input.setInputProcessor(new InputAdapter() {
            @Override public boolean touchDown(int sx, int sy, int p, int b) {
                camera.unproject(touch.set(sx, sy, 0), viewport.getScreenX(), viewport.getScreenY(),
                        viewport.getScreenWidth(), viewport.getScreenHeight());
                handle(touch.x, touch.y);
                return true;
            }
            @Override public boolean mouseMoved(int sx, int sy) {
                camera.unproject(touch.set(sx, sy, 0), viewport.getScreenX(), viewport.getScreenY(),
                        viewport.getScreenWidth(), viewport.getScreenHeight());
                mx = touch.x; my = touch.y; return false;
            }
            @Override public boolean scrolled(float ax, float ay) {
                listScrollY = Math.max(0, listScrollY + ay * Theme.SCROLL_STEP);
                return true;
            }
            @Override public boolean keyDown(int k) {
                if (UiKeys.isBackOrDismiss(k)) {
                    game.setScreen(new MainMenuScreen(game));
                    return true;
                }
                if (focus.key(k, Gdx.input.isKeyPressed(Input.Keys.SHIFT_LEFT)
                        || Gdx.input.isKeyPressed(Input.Keys.SHIFT_RIGHT))) return true;
                if (k == Input.Keys.ENTER || k == Input.Keys.SPACE) {
                    Rectangle cur = focus.current();
                    if (cur != null) handle(cur.x + 2, cur.y + 2);
                    return true;
                }
                if (tab == Tab.REPLAY) {
                    if (k == Input.Keys.LEFT) { scrubber.stepBy(-1); return true; }
                    if (k == Input.Keys.RIGHT) { scrubber.stepBy(+1); return true; }
                }
                if (tab == Tab.REWARD) {
                    if (k == Input.Keys.LEFT) { nudgeReward(-0.05); return true; }
                    if (k == Input.Keys.RIGHT) { nudgeReward(+0.05); return true; }
                    if (k == Input.Keys.UP) {
                        rewardTermIndex = Math.floorMod(rewardTermIndex - 1, RewardStudioModel.terms().length);
                        return true;
                    }
                    if (k == Input.Keys.DOWN) {
                        rewardTermIndex = Math.floorMod(rewardTermIndex + 1, RewardStudioModel.terms().length);
                        return true;
                    }
                }
                return false;
            }
        });
    }

    private void handle(float x, float y) {
        if (backBtn.contains(x, y)) { game.setScreen(new MainMenuScreen(game)); return; }
        for (int i = 0; i < tabBtns.length; i++) {
            if (tabBtns[i].contains(x, y)) { tab = Tab.values()[i]; return; }
        }
        switch (tab) {
            case REWARD -> handleReward(x, y);
            case DASHBOARD -> handleDash(x, y);
            case BENCH -> handleBench(x, y);
            case REPLAY -> handleReplay(x, y);
            case PHYSICS -> handlePhysics(x, y);
            case PLUGINS -> { /* informational */ }
        }
    }

    private void handleReward(float x, float y) {
        if (actionBtn.contains(x, y)) {
            String err = reward.validate();
            if (err != null) { toast.show(err, UiToast.Tone.WARNING, 3f); return; }
            try {
                Files.createDirectories(RunHistoryStore.rewardsDir());
                var out = RunHistoryStore.rewardsDir().resolve("preset-" + System.currentTimeMillis() + ".reward");
                Files.writeString(out, reward.exportText(), StandardCharsets.UTF_8);
                Gdx.app.getClipboard().setContents(reward.exportText());
                toast.show("Reward preset exported", UiToast.Tone.SUCCESS, 3f);
            } catch (Exception e) {
                toast.show("Export failed: " + e.getMessage(), UiToast.Tone.ERROR, 3f);
            }
            return;
        }
        if (action2Btn.contains(x, y)) {
            String err = reward.importText(Gdx.app.getClipboard().getContents());
            toast.show(err == null ? "Imported reward preset" : err,
                    err == null ? UiToast.Tone.SUCCESS : UiToast.Tone.WARNING, 3f);
            return;
        }
        // Row hit to select term.
        float y0 = Theme.VH - 180f + listScrollY;
        for (int i = 0; i < RewardStudioModel.terms().length; i++) {
            float rowY = y0 - i * 54f;
            if (y <= rowY && y >= rowY - 48f && x > 40 && x < Theme.VW - 40) {
                rewardTermIndex = i;
                if (x > Theme.VW / 2f) nudgeReward(x < Theme.VW * 0.75f ? -0.05 : +0.05);
                return;
            }
        }
    }

    private void nudgeReward(double delta) {
        String term = RewardStudioModel.terms()[rewardTermIndex];
        reward.setWeight(term, reward.weight(term) + delta);
    }

    private void handleDash(float x, float y) {
        if (actionBtn.contains(x, y)) {
            Gdx.app.getClipboard().setContents(dash.exportSummary());
            toast.show("Dashboard summary copied", UiToast.Tone.SUCCESS, 3f);
        }
    }

    private void handleBench(float x, float y) {
        if (actionBtn.contains(x, y) && !benchRunning) {
            benchRunning = true;
            benchStatus.set("Running bounded bench…");
            Thread t = new Thread(() -> {
                try {
                    List<String> lines = new ArrayList<>();
                    for (InAppBench.Result r : InAppBench.runDefaults()) lines.add(InAppBench.format(r));
                    benchStatus.set(String.join("\n", lines));
                } catch (Throwable e) {
                    benchStatus.set("Bench failed: " + e.getMessage());
                } finally {
                    benchRunning = false;
                }
            }, "suika-inapp-bench");
            t.setDaemon(true);
            t.start();
        }
    }

    private void handleReplay(float x, float y) {
        if (actionBtn.contains(x, y)) scrubber.stepBy(+1);
        if (action2Btn.contains(x, y)) scrubber.stepBy(-1);
    }

    private void handlePhysics(float x, float y) {
        if (actionBtn.contains(x, y)) {
            var snap = PhysicsGoldenTool.canonical();
            physicsNote = "Verified live · score=" + snap.score()
                    + " fruits=" + snap.fruits() + " steps=" + snap.steps();
            toast.show("Golden trajectory recomputed (fixture unchanged)", UiToast.Tone.INFO, 3f);
        }
        if (action2Btn.contains(x, y)) {
            try {
                var out = RunHistoryStore.root().resolve("golden-rebless.txt");
                PhysicsGoldenTool.writeReblessFile(out);
                Gdx.app.getClipboard().setContents(PhysicsGoldenTool.reblessSnippet(PhysicsGoldenTool.canonical()));
                toast.show("Rebless snippet copied — paste into GoldenPhysicsTest only after intentional physics change",
                        UiToast.Tone.WARNING, 5f);
            } catch (Exception e) {
                toast.show(e.getMessage(), UiToast.Tone.ERROR, 3f);
            }
        }
    }

    @Override
    public void render(float delta) {
        toast.tick(delta);
        Gdx.gl.glClearColor(0.05f, 0.06f, 0.10f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        Gdx.gl.glEnable(GL20.GL_BLEND);
        viewport.apply();
        game.shapes.setProjectionMatrix(camera.combined);
        game.batch.setProjectionMatrix(camera.combined);

        ShapeRenderer s = game.shapes;
        s.begin(ShapeRenderer.ShapeType.Filled);
        Ui.background(s, Theme.VW, Theme.VH);
        for (int i = 0; i < tabBtns.length; i++) {
            boolean on = Tab.values()[i] == tab;
            Ui.button(s, tabBtns[i], on ? Theme.ACCENT_2 : Theme.PANEL_EDGE,
                    tabBtns[i].contains(mx, my) || focus.isFocused(tabBtns[i]), true);
        }
        Ui.button(s, backBtn, Theme.ACCENT, backBtn.contains(mx, my), true);
        if (tab != Tab.PLUGINS)
            Ui.button(s, actionBtn, Theme.ACCENT_2, actionBtn.contains(mx, my), true);
        if (tab == Tab.REWARD || tab == Tab.REPLAY || tab == Tab.PHYSICS)
            Ui.button(s, action2Btn, Theme.GOLD, action2Btn.contains(mx, my), true);
        if (toast.visible()) toast.drawShapes(s, Theme.VW, 110f);
        focus.drawRing(s);
        s.end();

        game.batch.begin();
        Ui.textCenter(game.batch, game.fontMed, "RESEARCH LAB", Theme.VW / 2f, Theme.VH - 40, Theme.GOLD);
        String[] labels = {"Reward", "Dash", "Bench", "Replay", "Physics", "Plugins"};
        for (int i = 0; i < labels.length; i++) {
            Ui.textCenter(game.batch, game.fontSmall, labels[i],
                    tabBtns[i].x + tabBtns[i].width / 2f, tabBtns[i].y + 22, Theme.TEXT);
        }
        Ui.textCenter(game.batch, game.fontMed, "BACK",
                backBtn.x + backBtn.width / 2f, backBtn.y + 30, Theme.TEXT);
        drawTabBody();
        toast.drawText(game.batch, game.fontSmall, Theme.VW, 110f);
        game.batch.end();
    }

    private void drawTabBody() {
        float y = Theme.VH - 160f;
        switch (tab) {
            case REWARD -> {
                Ui.text(game.batch, game.fontSmall, "ComposableReward weights · ←/→ nudge · EXP/IMP",
                        40, y, Theme.TEXT_DIM);
                y -= 40;
                String[] terms = RewardStudioModel.terms();
                for (int i = 0; i < terms.length; i++) {
                    boolean sel = i == rewardTermIndex;
                    Ui.text(game.batch, game.font, terms[i], 48, y, sel ? Theme.GOLD : Theme.TEXT);
                    Ui.textRight(game.batch, game.font,
                            String.format(java.util.Locale.US, "%.3f", reward.weight(terms[i])),
                            Theme.VW - 48, y, Theme.TEXT);
                    y -= 48;
                }
                Ui.textCenter(game.batch, game.fontSmall, "EXPORT",
                        actionBtn.x + actionBtn.width / 2f, actionBtn.y + 28, Theme.TEXT);
                Ui.textCenter(game.batch, game.fontSmall, "IMPORT",
                        action2Btn.x + action2Btn.width / 2f, action2Btn.y + 28, Theme.BG_BOTTOM);
            }
            case DASHBOARD -> {
                Ui.text(game.batch, game.fontSmall,
                        "DashboardRegistry · " + dash.activeCount() + " active runs",
                        40, y, Theme.TEXT_DIM);
                y -= 36;
                for (var row : dash.rows()) {
                    if (y < 160) break;
                    Ui.text(game.batch, game.fontSmall,
                            row.algorithm() + " · " + row.id() + " · steps " + row.steps(),
                            48, y, Theme.TEXT);
                    y -= 28;
                }
                if (dash.rows().isEmpty()) {
                    Ui.statusCopy(game.batch, game.font, game.fontSmall,
                            Theme.VW / 2f, Theme.VH / 2f,
                            "No live runs", "Launch training from Control Center to populate");
                }
                Ui.textCenter(game.batch, game.fontSmall, "COPY",
                        actionBtn.x + actionBtn.width / 2f, actionBtn.y + 28, Theme.TEXT);
            }
            case BENCH -> {
                Ui.text(game.batch, game.fontSmall,
                        "Bounded in-app bench (CLI Bench.java unchanged)",
                        40, y, Theme.TEXT_DIM);
                y -= 40;
                for (String line : benchStatus.get().split("\\R")) {
                    Ui.text(game.batch, game.fontSmall, line, 48, y, Theme.TEXT);
                    y -= 26;
                }
                Ui.textCenter(game.batch, game.fontSmall, benchRunning ? "…" : "RUN",
                        actionBtn.x + actionBtn.width / 2f, actionBtn.y + 28, Theme.TEXT);
            }
            case REPLAY -> {
                var st = scrubber.stateAtCursor();
                Ui.text(game.batch, game.fontSmall,
                        "Replay scrub · seed " + scrubber.log().seed()
                                + " · step " + scrubber.cursor() + "/" + scrubber.length(),
                        40, y, Theme.TEXT_DIM);
                y -= 40;
                Ui.text(game.batch, game.fontMed, "Score " + st.score(), 48, y, Theme.GOLD);
                y -= 36;
                Ui.text(game.batch, game.fontSmall, "Fruits " + st.fruits().size()
                        + " · ←/→ scrub", 48, y, Theme.TEXT);
                Ui.textCenter(game.batch, game.fontSmall, "STEP+",
                        actionBtn.x + actionBtn.width / 2f, actionBtn.y + 28, Theme.TEXT);
                Ui.textCenter(game.batch, game.fontSmall, "STEP−",
                        action2Btn.x + action2Btn.width / 2f, action2Btn.y + 28, Theme.BG_BOTTOM);
            }
            case PHYSICS -> {
                Ui.text(game.batch, game.fontSmall,
                        "Golden physics tooling — never silently rewrite fixtures",
                        40, y, Theme.TEXT_DIM);
                y -= 40;
                Ui.text(game.batch, game.font, physicsNote, 48, y, Theme.TEXT);
                y -= 40;
                Ui.text(game.batch, game.fontSmall,
                        "VERIFY recomputes live; REBLESS copies a patch snippet only.",
                        48, y, Theme.TEXT_DIM);
                Ui.textCenter(game.batch, game.fontSmall, "VERIFY",
                        actionBtn.x + actionBtn.width / 2f, actionBtn.y + 28, Theme.TEXT);
                Ui.textCenter(game.batch, game.fontSmall, "REBLESS",
                        action2Btn.x + action2Btn.width / 2f, action2Btn.y + 28, Theme.BG_BOTTOM);
            }
            case PLUGINS -> {
                Ui.text(game.batch, game.fontSmall,
                        "PluginRegistry discovery (Researcher UI hooks)",
                        40, y, Theme.TEXT_DIM);
                y -= 36;
                int n = 0;
                for (var a : PluginRegistry.get().agents()) {
                    Ui.text(game.batch, game.font, a.displayName() + "  ·  " + a.id(),
                            48, y, Theme.TEXT);
                    y -= 28;
                    n++;
                    if (y < 160) break;
                }
                for (var t : PluginRegistry.get().trainers()) {
                    Ui.text(game.batch, game.fontSmall, "trainer · " + t.id(), 48, y, Theme.TEXT_DIM);
                    y -= 24;
                    n++;
                    if (y < 160) break;
                }
                if (n == 0) {
                    Ui.statusCopy(game.batch, game.font, game.fontSmall,
                            Theme.VW / 2f, Theme.VH / 2f,
                            "No third-party plugins", "Add META-INF/services AgentPlugin entries");
                }
                Ui.text(game.batch, game.fontSmall,
                        "Curated Explorer matrix is unchanged — plugins appear in Researcher mode.",
                        40, 140, Theme.TEXT_FAINT);
            }
        }
    }

    @Override public void resize(int w, int h) { viewport.update(w, h); }
    @Override public void hide() { Gdx.input.setInputProcessor(null); }
}
