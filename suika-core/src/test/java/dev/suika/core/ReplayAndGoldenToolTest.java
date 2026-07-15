package dev.suika.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ReplayScrubberTest {

    @Test
    void scrubSeeksDeterministically() {
        ReplayLog log = new ReplayLog(42L);
        for (double d : PhysicsGoldenTool.CANONICAL_DROPS) log.record(d, 0);
        ReplayScrubber scrub = new ReplayScrubber(log);
        scrub.seek(5);
        GameState mid = scrub.stateAtCursor();
        scrub.seek(5);
        GameState again = scrub.stateAtCursor();
        assertEquals(mid.score(), again.score());
        assertEquals(mid.fruits().size(), again.fruits().size());
        assertEquals(mid.stepCount(), again.stepCount());
    }

    @Test
    void textRoundTrip() {
        ReplayLog log = new ReplayLog(7L);
        log.record(1.5, 0);
        log.record(4.0, 0);
        ReplayLog back = ReplayScrubber.importText(ReplayScrubber.exportText(log));
        assertEquals(7L, back.seed());
        assertEquals(2, back.length());
    }
}

class PhysicsGoldenToolTest {
    @Test
    void canonicalSnapshotIsStable() {
        var a = PhysicsGoldenTool.canonical();
        var b = PhysicsGoldenTool.canonical();
        assertEquals(a.fingerprint(), b.fingerprint());
        assertTrue(PhysicsGoldenTool.reblessSnippet(a).contains("EXPECTED_SCORE"));
    }
}
