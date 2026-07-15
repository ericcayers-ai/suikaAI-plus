package dev.suika.game;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Factory defaults without touching LibGDX Preferences. */
class GameSettingsDefaultsTest {
    @Test
    void applyFactoryDefaultsResetsFlags() {
        GameSettings s = new GameSettings();
        s.fpsIndex = 5;
        s.reducedMotion = true;
        s.gpuMode = true;
        s.firstRunHelpSeen = true;
        s.customFps = 144;
        s.applyFactoryDefaults();
        assertEquals(1, s.fpsIndex);
        assertFalse(s.reducedMotion);
        assertFalse(s.gpuMode);
        assertFalse(s.firstRunHelpSeen);
        assertEquals(-1, s.customFps);
    }
}
