package dev.suika.game;

import com.badlogic.gdx.Gdx;
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

    private final Rectangle playBtn     = new Rectangle(CX - 170, 660, 340, 78);
    private final Rectangle watchBtn    = new Rectangle(CX - 170, 566, 340, 78);
    private final Rectangle settingsBtn = new Rectangle(CX - 170, 472, 340, 78);
    private final Rectangle quitBtn     = new Rectangle(CX - 170, 378, 340, 78);
    // Smaller and visually distinct (violet) — this launches a genuinely experimental
    // feature (raw Vulkan hardware ray tracing in a separate window/GPU context, not
    // the game's own OpenGL rendering) that may not work on every GPU/driver.
    private final Rectangle rtLabBtn    = new Rectangle(CX - 130, 296, 260, 56);
    // Opens the AI-save picker so any technique's saved slot (see AiSlotPlayer) can
    // play RT Lab by itself instead of a human — sits beside RT LAB in the margin
    // between it and the screen edge, same violet family, no layout shuffling needed.
    private final Rectangle rtAiBtn     = new Rectangle(CX + 140, 296, 150, 56);

    private float time = 0f;
    private float mx, my;
    /** Seconds left to show the "enable Experimental mode" hint under the RT button. */
    private float rtHintTimer = 0f;

    // ---- AI-save picker modal (RT Lab autoplay) ----
    private record SaveEntry(AiTechnique technique, int slot, ModelSlots.SlotInfo info) {}
    private boolean aiPickerOpen = false;
    private java.util.List<SaveEntry> aiSaves = java.util.List.of();
    private int aiScrollIndex = 0;
    private String aiPickerMessage = "";
    private static final int AI_ROWS_VISIBLE = 6;
    private static final float AI_MW = 600f, AI_MH = 500f;
    private final Rectangle[] aiRowBtn = new Rectangle[AI_ROWS_VISIBLE];
    private final Rectangle aiCloseBtn = new Rectangle();
    private final Rectangle aiScrollUpBtn = new Rectangle();
    private final Rectangle aiScrollDownBtn = new Rectangle();
    { for (int i = 0; i < AI_ROWS_VISIBLE; i++) aiRowBtn[i] = new Rectangle(); }

    // ambient floating fruit (x, baseY, speed, tier)
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
        Gdx.input.setInputProcessor(new InputAdapter() {
            @Override public boolean touchDown(int sx, int sy, int pointer, int button) {
                camera.unproject(touch.set(sx, sy, 0), viewport.getScreenX(), viewport.getScreenY(), viewport.getScreenWidth(), viewport.getScreenHeight());
                if (aiPickerOpen) { handleAiPickerClick(touch.x, touch.y); return true; }
                if (playBtn.contains(touch.x, touch.y))
                    game.setScreen(new SuikaScreen(game, SuikaScreen.Mode.HUMAN));
                else if (watchBtn.contains(touch.x, touch.y))
                    game.setScreen(new AiPlaygroundScreen(game));
                else if (settingsBtn.contains(touch.x, touch.y))
                    game.setScreen(new SettingsScreen(game, MainMenuScreen::new));
                else if (quitBtn.contains(touch.x, touch.y))
                    Gdx.app.exit();
                else if (rtLabBtn.contains(touch.x, touch.y)) {
                    // Gated behind the Experimental settings toggle — the ray-traced
                    // game (and its 3D-physics option) only exists in experimental mode.
                    if (game.settings.experimentalMode)
                        dev.suika.game.rtlab.RtLabLauncher.launch(game.settings.rt3dPhysics);
                    else
                        rtHintTimer = 3.5f;
                } else if (rtAiBtn.contains(touch.x, touch.y)) {
                    if (game.settings.experimentalMode) openAiPicker();
                    else rtHintTimer = 3.5f;
                }
                return true;
            }
            @Override public boolean mouseMoved(int sx, int sy) {
                camera.unproject(touch.set(sx, sy, 0), viewport.getScreenX(), viewport.getScreenY(), viewport.getScreenWidth(), viewport.getScreenHeight());
                mx = touch.x; my = touch.y;
                return false;
            }
        });
    }

    @Override
    public void render(float delta) {
        time += delta;
        Gdx.gl.glClearColor(0.05f, 0.06f, 0.10f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        Gdx.gl.glEnable(GL20.GL_BLEND);

        viewport.apply();
        game.shapes.setProjectionMatrix(camera.combined);
        game.batch.setProjectionMatrix(camera.combined);

        ShapeRenderer s = game.shapes;
        s.begin(ShapeRenderer.ShapeType.Filled);

        // backdrop gradient
        s.rect(0, 0, Theme.VW, Theme.VH,
                Theme.BG_BOTTOM, Theme.BG_BOTTOM, Theme.BG_TOP, Theme.BG_TOP);

        // ambient rising fruit
        for (float[] m : motes) {
            float y = (m[1] + time * m[2]) % (Theme.VH + 120) - 60;
            FruitTier t = FruitTier.values()[(int) m[3]];
            var c = FruitColors.of(t);
            float rr = 16 + m[3] * 6;
            s.setColor(c.r, c.g, c.b, 0.07f);
            s.circle(m[0], y, rr, 24);
        }

        // buttons
        Ui.button(s, playBtn,     Theme.ACCENT_2,    playBtn.contains(mx, my),     true);
        Ui.button(s, watchBtn,    Theme.ACCENT_BLUE, watchBtn.contains(mx, my),    true);
        Ui.button(s, settingsBtn, Theme.PANEL_EDGE,  settingsBtn.contains(mx, my), true);
        Ui.button(s, quitBtn,     Theme.ACCENT,      quitBtn.contains(mx, my),     true);
        // Dimmed until Experimental mode is enabled in Settings.
        if (game.settings.experimentalMode) {
            Ui.button(s, rtLabBtn, RT_LAB_VIOLET, rtLabBtn.contains(mx, my), true);
            Ui.button(s, rtAiBtn, Theme.ACCENT_2, rtAiBtn.contains(mx, my), true);
        } else {
            s.setColor(RT_LAB_VIOLET.r * 0.45f, RT_LAB_VIOLET.g * 0.45f, RT_LAB_VIOLET.b * 0.45f, 0.6f);
            Ui.fillRoundRect(s, rtLabBtn.x, rtLabBtn.y, rtLabBtn.width, rtLabBtn.height, 12f);
            Ui.fillRoundRect(s, rtAiBtn.x, rtAiBtn.y, rtAiBtn.width, rtAiBtn.height, 12f);
        }

        s.end();

        game.batch.begin();
        // Title
        Ui.textCenter(game.batch, game.fontHuge, "SUIKA", CX, 1010, Theme.GOLD);
        Ui.textCenter(game.batch, game.fontHuge, "AI SANDBOX", CX, 920, Theme.TEXT);
        Ui.textCenter(game.batch, game.font,
                "A faithful merge-puzzle clone fused with an AI laboratory",
                CX, 838, Theme.TEXT_DIM);

        // Button labels
        Ui.textCenter(game.batch, game.fontMed, "PLAY",     CX, playBtn.y + 39,     Theme.TEXT);
        Ui.textCenter(game.batch, game.fontMed, "WATCH AI", CX, watchBtn.y + 39,    Theme.TEXT);
        Ui.textCenter(game.batch, game.fontMed, "SETTINGS", CX, settingsBtn.y + 39, Theme.TEXT);
        Ui.textCenter(game.batch, game.fontMed, "QUIT",     CX, quitBtn.y + 39,     Theme.TEXT);
        if (game.settings.experimentalMode) {
            String mode = game.settings.rt3dPhysics ? "3D" : "2D";
            Ui.textCenter(game.batch, game.fontSmall, "RT LAB · " + mode + " (experimental)",
                    CX, rtLabBtn.y + 30, Theme.TEXT);
        } else {
            Ui.textCenter(game.batch, game.fontSmall, "RT LAB (experimental)", CX, rtLabBtn.y + 30, Theme.TEXT_DIM);
        }
        Ui.textCenter(game.batch, game.fontSmall, "AI PLAYS →",
                rtAiBtn.x + rtAiBtn.width / 2f, rtAiBtn.y + 26,
                game.settings.experimentalMode ? Theme.BG_BOTTOM : Theme.TEXT_FAINT);
        if (rtHintTimer > 0f) {
            rtHintTimer -= delta;
            Ui.textCenter(game.batch, game.fontSmall, "Enable Experimental mode in Settings first",
                    CX, rtLabBtn.y - 12, Theme.GOLD);
        }

        // Footer
        Ui.textCenter(game.batch, game.fontSmall,
                "Click / drag to aim · ESC pauses · R restarts", CX, 250, Theme.TEXT_FAINT);
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
        aiCloseBtn.set(m0x + AI_MW / 2f - 90, m0y + 20, 180, 44);
    }

    private void handleAiPickerClick(float x, float y) {
        if (aiCloseBtn.contains(x, y)) { aiPickerOpen = false; return; }
        if (aiScrollUpBtn.contains(x, y)) { aiScrollIndex = Math.max(0, aiScrollIndex - AI_ROWS_VISIBLE); return; }
        if (aiScrollDownBtn.contains(x, y)) {
            if (aiScrollIndex + AI_ROWS_VISIBLE < aiSaves.size()) aiScrollIndex += AI_ROWS_VISIBLE;
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
                aiPickerOpen = false;
                return;
            }
        }
        float m0x = aiModalX(), m0y = aiModalY();
        if (x < m0x || x > m0x + AI_MW || y < m0y || y > m0y + AI_MH) aiPickerOpen = false;
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
                    "No AI saves yet — save one from Watch AI's SLOTS panel.",
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
        if (!aiPickerMessage.isEmpty()) Ui.textCenter(game.batch, game.fontSmall, aiPickerMessage,
                m0x + AI_MW / 2f, m0y + 78, Theme.GOLD);
        Ui.textCenter(game.batch, game.fontSmall, "CLOSE",
                aiCloseBtn.x + aiCloseBtn.width / 2f, aiCloseBtn.y + 25, Theme.TEXT);
        game.batch.end();
    }
}
