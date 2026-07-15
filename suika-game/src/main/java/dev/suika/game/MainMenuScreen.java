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
import dev.suika.core.FruitTier;

/**
 * Title screen: Play, Watch AI, Settings, Quit — with a gentle ambient backdrop of
 * rising translucent fruit so the menu feels alive.
 */
public final class MainMenuScreen extends ScreenAdapter {

    private final SuikaGame          game;
    private final OrthographicCamera camera = new OrthographicCamera();
    private final FitViewport        viewport;
    private final Vector3            touch = new Vector3();

    private static final float CX = Theme.VW / 2f;
    private static final com.badlogic.gdx.graphics.Color RT_LAB_VIOLET =
            new com.badlogic.gdx.graphics.Color(0.55f, 0.35f, 0.85f, 1f);

    private final Rectangle playBtn     = new Rectangle(CX - 170, 680, 340, 78);
    private final Rectangle watchBtn    = new Rectangle(CX - 170, 586, 340, 78);
    private final Rectangle settingsBtn = new Rectangle(CX - 170, 492, 340, 78);
    private final Rectangle labBtn      = new Rectangle(CX - 170, 398, 340, 78);
    private final Rectangle quitBtn     = new Rectangle(CX - 170, 304, 340, 70);
    private final Rectangle rtLabBtn    = new Rectangle(CX - 130, 220, 260, 48);
    private final Rectangle rtAiBtn     = new Rectangle(CX + 140, 220, 150, 48);

    private final UiFocus focus = new UiFocus();
    private boolean helpOpen = false;
    private final Rectangle helpCloseBtn = new Rectangle(CX - 100, 360, 200, 56);
    private final Rectangle helpDismissBtn = new Rectangle(CX - 140, 430, 280, 56);
    private final Rectangle rtRetryBtn = new Rectangle(CX - 100, 160, 200, 40);
    private final Rectangle rtSettingsBtn = new Rectangle(CX + 110, 160, 120, 40);

    private float time = 0f;
    private float mx, my;
    private float rtHintTimer = 0f;
    private String rtHintText = "";
    private boolean awaitingRtLaunch = false;
    private boolean rtFailureActionable = false;

    // ---- AI-save picker modal (RT Lab autoplay) ----
    private record SaveEntry(AiTechnique technique, int slot, ModelSlots.SlotInfo info) {}
    private boolean aiPickerOpen = false;
    private java.util.List<SaveEntry> aiSaves = java.util.List.of();
    private int aiScrollIndex = 0;
    private String aiPickerMessage = "";
    private static final int AI_ROWS_VISIBLE = 6;

    // Heightened panel geometry to accommodate the browse button
    private static final float AI_MW = 600f, AI_MH = 560f;
    private final Rectangle[] aiRowBtn = new Rectangle[AI_ROWS_VISIBLE];
    private final Rectangle aiCloseBtn = new Rectangle();
    private final Rectangle aiScrollUpBtn = new Rectangle();
    private final Rectangle aiScrollDownBtn = new Rectangle();
    private final Rectangle aiBrowseBtn = new Rectangle();
    { for (int i = 0; i < AI_ROWS_VISIBLE; i++) aiRowBtn[i] = new Rectangle(); }

    private final float[][] motes = new float[9][];

    public MainMenuScreen(SuikaGame game) {
        this.game = game;
        viewport = new FitViewport(Theme.VW, Theme.VH, camera);
        camera.position.set(CX, Theme.VH / 2f, 0f);
        camera.update();
        java.util.Random r = new java.util.Random(7);
        for (int i = 0; i < motes.length; i++) {
            motes[i] = new float[]{
                    40 + r.nextFloat() * (Theme.VW - 80),
                    r.nextFloat() * Theme.VH,
                    14 + r.nextFloat() * 26,
                    r.nextInt(FruitTier.values().length)
            };
        }
    }

    @Override
    public void show() {
        helpOpen = !game.settings.firstRunHelpSeen;
        focus.clear();
        focus.add(playBtn); focus.add(watchBtn); focus.add(settingsBtn);
        focus.add(labBtn); focus.add(quitBtn); focus.add(rtLabBtn); focus.add(rtAiBtn);
        focus.ensureStarted();
        Gdx.input.setInputProcessor(new InputAdapter() {
            @Override public boolean touchDown(int sx, int sy, int pointer, int button) {
                camera.unproject(touch.set(sx, sy, 0), viewport.getScreenX(), viewport.getScreenY(), viewport.getScreenWidth(), viewport.getScreenHeight());
                if (helpOpen) {
                    if (helpDismissBtn.contains(touch.x, touch.y) || helpCloseBtn.contains(touch.x, touch.y)) {
                        helpOpen = false;
                        game.settings.firstRunHelpSeen = true;
                        SettingsPersistence.save(game.settings);
                    }
                    return true;
                }
                if (aiPickerOpen) { handleAiPickerClick(touch.x, touch.y); return true; }
                if (rtFailureActionable && rtHintTimer > 0f) {
                    if (rtRetryBtn.contains(touch.x, touch.y)) {
                        dev.suika.game.rtlab.RtLabLauncher.launch(game.settings.rt3dPhysics);
                        awaitingRtLaunch = true;
                        rtFailureActionable = false;
                        rtHintTimer = 0f;
                        return true;
                    }
                    if (rtSettingsBtn.contains(touch.x, touch.y)) {
                        game.setScreen(new SettingsScreen(game, MainMenuScreen::new));
                        return true;
                    }
                }
                if (playBtn.contains(touch.x, touch.y))
                    game.setScreen(new SuikaScreen(game, SuikaScreen.Mode.HUMAN));
                else if (watchBtn.contains(touch.x, touch.y))
                    game.setScreen(new AiPlaygroundScreen(game));
                else if (settingsBtn.contains(touch.x, touch.y))
                    game.setScreen(new SettingsScreen(game, MainMenuScreen::new));
                else if (labBtn.contains(touch.x, touch.y))
                    game.setScreen(new LabHubScreen(game));
                else if (quitBtn.contains(touch.x, touch.y))
                    Gdx.app.exit();
                else if (rtLabBtn.contains(touch.x, touch.y)) {
                    dev.suika.game.rtlab.RtLabLauncher.launch(game.settings.rt3dPhysics);
                    awaitingRtLaunch = true;
                } else if (rtAiBtn.contains(touch.x, touch.y)) {
                    openAiPicker();
                }
                return true;
            }
            @Override public boolean mouseMoved(int sx, int sy) {
                camera.unproject(touch.set(sx, sy, 0), viewport.getScreenX(), viewport.getScreenY(), viewport.getScreenWidth(), viewport.getScreenHeight());
                mx = touch.x; my = touch.y;
                return false;
            }
            @Override public boolean keyDown(int k) {
                if (helpOpen) {
                    if (UiKeys.isBackOrDismiss(k) || k == Input.Keys.ENTER || k == Input.Keys.SPACE) {
                        helpOpen = false;
                        game.settings.firstRunHelpSeen = true;
                        SettingsPersistence.save(game.settings);
                        return true;
                    }
                    return true;
                }
                if (aiPickerOpen) {
                    if (UiKeys.isBackOrDismiss(k)) { aiPickerOpen = false; return true; }
                    return false;
                }
                if (focus.key(k, Gdx.input.isKeyPressed(Input.Keys.SHIFT_LEFT)
                        || Gdx.input.isKeyPressed(Input.Keys.SHIFT_RIGHT))) return true;
                if (k == Input.Keys.ENTER || k == Input.Keys.SPACE) {
                    Rectangle cur = focus.current();
                    if (cur == playBtn) game.setScreen(new SuikaScreen(game, SuikaScreen.Mode.HUMAN));
                    else if (cur == watchBtn) game.setScreen(new AiPlaygroundScreen(game));
                    else if (cur == settingsBtn) game.setScreen(new SettingsScreen(game, MainMenuScreen::new));
                    else if (cur == labBtn) game.setScreen(new LabHubScreen(game));
                    else if (cur == quitBtn) Gdx.app.exit();
                    else if (cur == rtLabBtn) {
                        dev.suika.game.rtlab.RtLabLauncher.launch(game.settings.rt3dPhysics);
                        awaitingRtLaunch = true;
                    } else if (cur == rtAiBtn) openAiPicker();
                    return true;
                }
                if (UiKeys.isBackOrDismiss(k)) { Gdx.app.exit(); return true; }
                return false;
            }
        });
    }

    @Override
    public void render(float delta) {
        time += delta;
        if (awaitingRtLaunch) {
            if (dev.suika.game.rtlab.RtLabLauncher.isRunning()) {
                awaitingRtLaunch = false;
            } else {
                String fail = dev.suika.game.rtlab.RtLabLauncher.lastFailure();
                if (fail != null) {
                    rtHintText = fail + "  ·  Retry or open Settings → RT Lab";
                    rtHintTimer = 8f;
                    rtFailureActionable = true;
                    awaitingRtLaunch = false;
                }
            }
        }
        Gdx.gl.glClearColor(0.05f, 0.06f, 0.10f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        Gdx.gl.glEnable(GL20.GL_BLEND);

        viewport.apply();
        game.shapes.setProjectionMatrix(camera.combined);
        game.batch.setProjectionMatrix(camera.combined);

        ShapeRenderer s = game.shapes;
        s.begin(ShapeRenderer.ShapeType.Filled);

        s.rect(0, 0, Theme.VW, Theme.VH,
                Theme.BG_BOTTOM, Theme.BG_BOTTOM, Theme.BG_TOP, Theme.BG_TOP);

        for (float[] m : motes) {
            float y = (m[1] + time * m[2]) % (Theme.VH + 120) - 60;
            FruitTier t = FruitTier.values()[(int) m[3]];
            var c = FruitColors.of(t);
            float rr = 16 + m[3] * 6;
            s.setColor(c.r, c.g, c.b, 0.07f);
            s.circle(m[0], y, rr, 24);
        }

        Ui.button(s, playBtn,     Theme.ACCENT_2,    playBtn.contains(mx, my) || focus.isFocused(playBtn),     true);
        Ui.button(s, watchBtn,    Theme.ACCENT_BLUE, watchBtn.contains(mx, my) || focus.isFocused(watchBtn),    true);
        Ui.button(s, settingsBtn, Theme.PANEL_EDGE,  settingsBtn.contains(mx, my) || focus.isFocused(settingsBtn), true);
        Ui.button(s, labBtn,      Theme.GOLD,        labBtn.contains(mx, my) || focus.isFocused(labBtn), true);
        Ui.button(s, quitBtn,     Theme.ACCENT,      quitBtn.contains(mx, my) || focus.isFocused(quitBtn),     true);
        Ui.button(s, rtLabBtn, RT_LAB_VIOLET, rtLabBtn.contains(mx, my) || focus.isFocused(rtLabBtn), true);
        Ui.button(s, rtAiBtn, Theme.ACCENT_2, rtAiBtn.contains(mx, my) || focus.isFocused(rtAiBtn), true);
        focus.drawRing(s);

        if (helpOpen) {
            Ui.modalScrim(s, Theme.VW, Theme.VH);
            Ui.modalCard(s, CX - 280, 340, 560, 420);
            Ui.button(s, helpDismissBtn, Theme.ACCENT_2, helpDismissBtn.contains(mx, my), true);
        }
        if (rtFailureActionable && rtHintTimer > 0f) {
            Ui.button(s, rtRetryBtn, Theme.ACCENT_2, rtRetryBtn.contains(mx, my), true);
            Ui.button(s, rtSettingsBtn, Theme.PANEL_EDGE, rtSettingsBtn.contains(mx, my), true);
        }

        s.end();

        game.batch.begin();
        Ui.textCenter(game.batch, game.fontHuge, "SUIKA", CX, 1010, Theme.GOLD);
        Ui.textCenter(game.batch, game.fontHuge, "AI SANDBOX", CX, 920, Theme.TEXT);
        Ui.textCenter(game.batch, game.font,
                "A faithful merge-puzzle clone fused with an AI laboratory",
                CX, 838, Theme.TEXT_DIM);

        Ui.textCenter(game.batch, game.fontMed, "PLAY",     CX, playBtn.y + 39,     Theme.TEXT);
        Ui.textCenter(game.batch, game.fontMed, "WATCH AI", CX, watchBtn.y + 39,    Theme.TEXT);
        Ui.textCenter(game.batch, game.fontMed, "SETTINGS", CX, settingsBtn.y + 39, Theme.TEXT);
        Ui.textCenter(game.batch, game.fontMed, "LAB",      CX, labBtn.y + 39,      Theme.BG_BOTTOM);
        Ui.textCenter(game.batch, game.fontMed, "QUIT",     CX, quitBtn.y + 35,     Theme.TEXT);
        String mode = game.settings.rt3dPhysics ? "3D" : "2D";
        Ui.textCenter(game.batch, game.fontSmall, "RT LAB · " + mode,
                CX, rtLabBtn.y + 30, Theme.TEXT);
        Ui.textCenter(game.batch, game.fontSmall, "AI PLAYS ->",
                rtAiBtn.x + rtAiBtn.width / 2f, rtAiBtn.y + 26, Theme.BG_BOTTOM);
        if (rtHintTimer > 0f) {
            rtHintTimer -= delta;
            Ui.textCenter(game.batch, game.fontSmall, rtHintText,
                    CX, rtLabBtn.y - 12, Theme.GOLD);
            if (rtFailureActionable) {
                Ui.textCenter(game.batch, game.fontSmall, "RETRY",
                        rtRetryBtn.x + rtRetryBtn.width / 2f, rtRetryBtn.y + 22, Theme.TEXT);
                Ui.textCenter(game.batch, game.fontSmall, "SETTINGS",
                        rtSettingsBtn.x + rtSettingsBtn.width / 2f, rtSettingsBtn.y + 22, Theme.TEXT);
            }
        }

        Ui.textCenter(game.batch, game.fontSmall,
                "Tab focus · Enter select · Mouse or ←/→ · Space drops · ESC pauses · R restarts",
                CX, 250, Theme.TEXT_FAINT);
        if (helpOpen) {
            Ui.textCenter(game.batch, game.fontMed, "Welcome to Suika AI+", CX, 720, Theme.GOLD);
            Ui.textCenter(game.batch, game.fontSmall, "PLAY — human watermelon merge puzzle", CX, 660, Theme.TEXT);
            Ui.textCenter(game.batch, game.fontSmall, "WATCH AI — pick a technique, tune, LAUNCH", CX, 620, Theme.TEXT);
            Ui.textCenter(game.batch, game.fontSmall, "SETTINGS — display, sim, AI, RT Lab, data", CX, 580, Theme.TEXT);
            Ui.textCenter(game.batch, game.fontSmall, "LAB — reward, dashboard, bench, replay, physics", CX, 540, Theme.TEXT);
            Ui.textCenter(game.batch, game.fontSmall, "RT LAB — ray-traced board (needs Vulkan RT)", CX, 500, Theme.TEXT);
            Ui.textCenter(game.batch, game.fontSmall, "Tab / Enter for keyboard-first navigation", CX, 500, Theme.TEXT_DIM);
            Ui.textCenter(game.batch, game.fontMed, "GOT IT",
                    helpDismissBtn.x + helpDismissBtn.width / 2f, helpDismissBtn.y + 28, Theme.TEXT);
        }
        Ui.text(game.batch, game.fontSmall, "v" + Theme.VERSION, 14, 30, Theme.TEXT_FAINT);
        Ui.textRight(game.batch, game.fontSmall,
                AiTechnique.values().length + " AI techniques · " + game.settings.fpsLabel(),
                Theme.VW - 14, 30, Theme.TEXT_FAINT);
        game.batch.end();

        if (aiPickerOpen) drawAiPicker();
    }

    @Override public void resize(int width, int height) { viewport.update(width, height); }
    @Override public void hide() { Gdx.input.setInputProcessor(null); }

    // -------------------------------------------------------------------------
    // AI-save picker — pick any technique's saved slot to autoplay RT Lab.
    // -------------------------------------------------------------------------

    private void openAiPicker() {
        java.util.List<SaveEntry> found = new java.util.ArrayList<>();
        for (AiTechnique t : AiTechnique.values()) {
            for (int slot = 1; slot <= ModelSlots.SLOT_COUNT; slot++) {
                ModelSlots.SlotInfo info = ModelSlots.info(t.id, slot);
                if (info.present()) found.add(new SaveEntry(t, slot, info));
            }
        }
        found.sort((a, b) -> Long.compare(b.info().savedAtMillis(), a.info().savedAtMillis()));
        aiSaves = found;
        aiScrollIndex = 0;
        aiPickerMessage = "";
        aiPickerOpen = true;
        layoutAiPicker();
    }

    private float aiModalX() { return (Theme.VW - AI_MW) / 2f; }
    private float aiModalY() { return (Theme.VH - AI_MH) / 2f; }

    private void layoutAiPicker() {
        float m0x = aiModalX(), m0y = aiModalY();
        for (int i = 0; i < AI_ROWS_VISIBLE; i++) {
            float rowY = m0y + AI_MH - 110 - i * 56;
            aiRowBtn[i].set(m0x + 24, rowY, AI_MW - 48, 46);
        }
        aiScrollUpBtn.set(m0x + AI_MW - 84, m0y + AI_MH - 44, 30, 30);
        aiScrollDownBtn.set(m0x + AI_MW - 48, m0y + AI_MH - 44, 30, 30);

        // Browse button placed below the rows but above the close button
        aiBrowseBtn.set(m0x + 24, m0y + 74, AI_MW - 48, 44);
        aiCloseBtn.set(m0x + AI_MW / 2f - 90, m0y + 16, 180, 44);
    }

    private void handleAiPickerClick(float x, float y) {
        if (aiCloseBtn.contains(x, y)) { aiPickerOpen = false; return; }
        if (aiScrollUpBtn.contains(x, y)) { aiScrollIndex = Math.max(0, aiScrollIndex - AI_ROWS_VISIBLE); return; }
        if (aiScrollDownBtn.contains(x, y)) {
            if (aiScrollIndex + AI_ROWS_VISIBLE < aiSaves.size()) aiScrollIndex += AI_ROWS_VISIBLE;
            return;
        }

        // Trigger standard system open dialog on browse click
        if (aiBrowseBtn.contains(x, y)) {
            triggerFileBrowse();
            return;
        }

        for (int i = 0; i < AI_ROWS_VISIBLE; i++) {
            int idx = aiScrollIndex + i;
            if (idx >= aiSaves.size()) break;
            if (aiRowBtn[i].contains(x, y)) {
                SaveEntry e = aiSaves.get(idx);
                dev.suika.ai.AgentPlugin driver = AiSlotPlayer.load(e.technique(), e.slot());
                if (driver == null) { aiPickerMessage = "Couldn't load that slot"; return; }
                dev.suika.game.rtlab.RtLabLauncher.launch(game.settings.rt3dPhysics, driver);
                awaitingRtLaunch = true;
                aiPickerOpen = false;
                return;
            }
        }
        float m0x = aiModalX(), m0y = aiModalY();
        if (x < m0x || x > m0x + AI_MW || y < m0y || y > m0y + AI_MH) aiPickerOpen = false;
    }

    /**
     * Triggers a native system file explorer dialog using JDK's standard AWT FileDialog
     * (Fully supported on Windows, macOS, and Linux out-of-the-box with zero LWJGL dependency issues).
     */
    private void triggerFileBrowse() {
        try {
            java.awt.FileDialog dialog = new java.awt.FileDialog(
                    (java.awt.Frame) null,
                    "Load Suika AI Save (.sav)",
                    java.awt.FileDialog.LOAD
            );
            dialog.setFilenameFilter((dir, name) -> name.endsWith(".sav"));
            dialog.setDirectory(System.getProperty("user.home") + java.io.File.separator + ".suikai" + java.io.File.separator + "saves");
            dialog.setVisible(true);

            String directory = dialog.getDirectory();
            String file = dialog.getFile();

            if (directory != null && file != null) {
                java.nio.file.Path p = java.nio.file.Paths.get(directory, file);
                dev.suika.ai.AgentPlugin driver = ModelSlots.loadFromFile(p);
                if (driver == null) {
                    aiPickerMessage = "Couldn't load: invalid or corrupt .sav / model files";
                } else {
                    dev.suika.game.rtlab.RtLabLauncher.launch(game.settings.rt3dPhysics, driver);
                    awaitingRtLaunch = true;
                    aiPickerOpen = false;
                }
            }
        } catch (Exception e) {
            aiPickerMessage = "Error opening file browser: " + e.getMessage();
        }
    }

    private void drawAiPicker() {
        layoutAiPicker();
        float m0x = aiModalX(), m0y = aiModalY();

        ShapeRenderer s = game.shapes;
        s.begin(ShapeRenderer.ShapeType.Filled);
        s.setColor(0f, 0f, 0f, 0.86f);
        s.rect(0, 0, Theme.VW, Theme.VH);
        s.setColor(0.08f, 0.09f, 0.13f, 1f);
        Ui.fillRoundRect(s, m0x, m0y, AI_MW, AI_MH, 16);
        Ui.panel(s, m0x, m0y, AI_MW, AI_MH, 16, Theme.PANEL_DEEP, Theme.PANEL_EDGE);
        s.setColor(RT_LAB_VIOLET);
        Ui.fillRoundRect(s, m0x, m0y + AI_MH - 4f, AI_MW, 4f, 3f);

        for (int i = 0; i < AI_ROWS_VISIBLE; i++) {
            int idx = aiScrollIndex + i;
            if (idx >= aiSaves.size()) break;
            s.setColor(aiRowBtn[i].contains(mx, my) ? Theme.PANEL_EDGE : Theme.PANEL);
            Ui.fillRoundRect(s, aiRowBtn[i].x, aiRowBtn[i].y, aiRowBtn[i].width, aiRowBtn[i].height, 8);
        }
        boolean hasMore = aiScrollIndex + AI_ROWS_VISIBLE < aiSaves.size();
        s.setColor(aiScrollIndex > 0 ? Theme.ACCENT_BLUE : Theme.PANEL_EDGE);
        Ui.fillRoundRect(s, aiScrollUpBtn.x, aiScrollUpBtn.y, aiScrollUpBtn.width, aiScrollUpBtn.height, 6);
        s.setColor(hasMore ? Theme.ACCENT_BLUE : Theme.PANEL_EDGE);
        Ui.fillRoundRect(s, aiScrollDownBtn.x, aiScrollDownBtn.y, aiScrollDownBtn.width, aiScrollDownBtn.height, 6);

        s.setColor(aiBrowseBtn.contains(mx, my) ? Theme.ACCENT_BLUE : Theme.PANEL);
        Ui.fillRoundRect(s, aiBrowseBtn.x, aiBrowseBtn.y, aiBrowseBtn.width, aiBrowseBtn.height, 8);
        s.setColor(aiBrowseBtn.contains(mx, my) ? Theme.TEXT : Theme.PANEL_EDGE);
        Ui.fillRoundRect(s, aiBrowseBtn.x + 3, aiBrowseBtn.y + 3, aiBrowseBtn.width - 6, aiBrowseBtn.height - 6, 6);

        boolean ch = aiCloseBtn.contains(mx, my);
        s.setColor(0f, 0f, 0f, 0.35f);
        Ui.fillRoundRect(s, aiCloseBtn.x + 3, aiCloseBtn.y - 4, aiCloseBtn.width, aiCloseBtn.height, 14);
        s.setColor(ch ? 1f : 0.92f, ch ? 0.40f : 0.32f, ch ? 0.43f : 0.36f, 1f);
        Ui.fillRoundRect(s, aiCloseBtn.x, aiCloseBtn.y, aiCloseBtn.width, aiCloseBtn.height, 14);
        s.end();

        game.batch.begin();
        Ui.textCenter(game.batch, game.fontMed, "PLAY AS: PICK A SAVED AI",
                m0x + AI_MW / 2f, m0y + AI_MH - 32, Theme.TEXT);
        Ui.textCenter(game.batch, game.fontSmall, "−", aiScrollUpBtn.x + 15, aiScrollUpBtn.y + 20, Theme.TEXT);
        Ui.textCenter(game.batch, game.fontSmall, "+", aiScrollDownBtn.x + 15, aiScrollDownBtn.y + 20, Theme.TEXT);

        if (aiSaves.isEmpty()) {
            Ui.textCenter(game.batch, game.fontSmall,
                    "No AI saves yet — save one from Watch AI's SAVES panel.",
                    m0x + AI_MW / 2f, m0y + AI_MH / 2f, Theme.TEXT_DIM);
        } else {
            for (int i = 0; i < AI_ROWS_VISIBLE; i++) {
                int idx = aiScrollIndex + i;
                if (idx >= aiSaves.size()) break;
                SaveEntry e = aiSaves.get(idx);
                float ty = aiRowBtn[i].y + aiRowBtn[i].height / 2f + 6f;
                Ui.text(game.batch, game.font, e.technique().display + "  ·  slot " + e.slot(),
                        aiRowBtn[i].x + 16, ty, Theme.TEXT);
                String detail = String.format("saved %tR  ·  score %.0f",
                        new java.util.Date(e.info().savedAtMillis()), e.info().score());
                Ui.text(game.batch, game.fontSmall, detail, aiRowBtn[i].x + 16, ty - 20f, Theme.TEXT_DIM);
            }
        }

        Ui.textCenter(game.batch, game.fontSmall, "BROWSE LOCAL FILE (.sav)...",
                aiBrowseBtn.x + aiBrowseBtn.width / 2f, aiBrowseBtn.y + aiBrowseBtn.height / 2f - 5f, Theme.TEXT);

        if (!aiPickerMessage.isEmpty()) Ui.textCenter(game.batch, game.fontSmall, aiPickerMessage,
                m0x + AI_MW / 2f, m0y + 128, Theme.GOLD);
        Ui.textCenter(game.batch, game.fontSmall, "CLOSE",
                aiCloseBtn.x + aiCloseBtn.width / 2f, aiCloseBtn.y + 25, Theme.TEXT);
        game.batch.end();
    }
}