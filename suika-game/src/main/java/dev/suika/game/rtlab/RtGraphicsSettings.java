package dev.suika.game.rtlab;

/**
 * Live-updating RT Lab graphics settings, all reachable from the in-window pause
 * menu. Deliberately scoped to knobs that don't need a swapchain rebuild
 * (resolution/fullscreen changes belong in the main app's Settings screen and take
 * effect on RT Lab's next launch) — every control here really does apply live,
 * no restart, the moment it's clicked:
 * <ul>
 *   <li><b>Bloom</b> — the bloom compute pass vs a plain image copy.</li>
 *   <li><b>Denoise</b> — the bilateral denoiser vs the raw traced frame.</li>
 *   <li><b>Depth of field</b> — the thin-lens aperture (0 = pinhole/everything sharp).</li>
 *   <li><b>Accumulation</b> — the temporal EMA blend factor (Off = every frame
 *       written through fully; higher = smoother but ghostier under motion).</li>
 * </ul>
 */
final class RtGraphicsSettings {

    boolean bloom = true;
    boolean denoise = true;

    private static final String[] DOF_LABELS   = {"OFF", "SUBTLE", "CINEMATIC"};
    private static final float[]  DOF_APERTURE = {0f, 0.30f, 0.55f};
    int dofIndex = 2;   // default: the cinematic look the scene was framed for

    private static final String[] ACCUM_LABELS = {"OFF", "LOW", "STANDARD", "HIGH"};
    private static final float[]  ACCUM_BLEND  = {1.0f, 0.6f, 0.30f, 0.15f};
    int accumIndex = 2; // default: the pre-v0.12 fixed 0.30 blend

    /** Bumped on every change so the HUD's redraw-only-when-dirty check stays one int. */
    int revision = 0;

    float aperture()   { return DOF_APERTURE[dofIndex]; }
    float accumBlend() { return ACCUM_BLEND[accumIndex]; }
    String dofLabel()   { return DOF_LABELS[dofIndex]; }
    String accumLabel() { return ACCUM_LABELS[accumIndex]; }

    void toggleBloom()   { bloom = !bloom; revision++; }
    void toggleDenoise() { denoise = !denoise; revision++; }
    void cycleDof()      { dofIndex = (dofIndex + 1) % DOF_APERTURE.length; revision++; }
    void cycleAccum()    { accumIndex = (accumIndex + 1) % ACCUM_BLEND.length; revision++; }
}
