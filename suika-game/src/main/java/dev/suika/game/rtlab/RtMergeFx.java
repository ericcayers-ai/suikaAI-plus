package dev.suika.game.rtlab;

import dev.suika.core.FruitTier;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Visual feedback for a merge — the RT scene previously just had the smaller pair
 * vanish and the bigger fruit appear with no transition at all. Two effects, both
 * expressed as extra short-lived {@link RtScene.FruitInstance}s appended to the
 * normal per-frame fruit list (no new material/mesh plumbing needed — see
 * {@link RtLabLauncher}'s render loop):
 *
 * <ul>
 *   <li><b>Coalesce flash</b> — one oversized, fast-shrinking sphere at the merge
 *       point, in the result tier's color, giving the "energy release" beat real
 *       physics can't (the actual merge is instantaneous).</li>
 *   <li><b>Particle burst</b> — a handful of tiny spheres flung outward and down
 *       under gravity, shrinking and vanishing over their lifetime.</li>
 * </ul>
 *
 * <p>Particle count is hard-capped ({@link #MAX_PARTICLES}) — under a torrent of
 * rapid merges (e.g. an AI-driven chain reaction) the oldest particles are culled
 * rather than letting the list, and therefore the per-frame TLAS rebuild, grow
 * without bound.
 */
final class RtMergeFx {

    private static final int MAX_PARTICLES = 40;
    private static final int PARTICLES_PER_MERGE = 7;
    private static final float PARTICLE_LIFE = 0.5f;
    private static final float FLASH_LIFE = 0.22f;
    private static final float GRAVITY = 9.0f;

    private record Particle(float x, float y, float z, float vx, float vy, float vz,
                             float age, float life, float baseRadius, FruitTier tier) {
        Particle stepped(float dt) {
            return new Particle(x + vx * dt, y + vy * dt, z + vz * dt,
                    vx * 0.92f, vy - GRAVITY * dt, vz * 0.92f, age + dt, life, baseRadius, tier);
        }
        boolean expired() { return age >= life; }
        float sizeFrac() { return Math.max(0f, 1f - age / life); }
    }

    private final List<Particle> particles = new ArrayList<>();
    private final List<Particle> flashes = new ArrayList<>();
    private final Random rng = new Random();

    /** Call once per frame after draining the session's new merges. */
    void spawn(List<RtGameSession.MergeInfo> merges) {
        for (RtGameSession.MergeInfo m : merges) {
            flashes.add(new Particle(m.x(), m.y(), m.z(), 0, 0, 0, 0f, FLASH_LIFE,
                    m.resultTier().radius * 1.35f, m.resultTier()));
            for (int i = 0; i < PARTICLES_PER_MERGE; i++) {
                while (particles.size() >= MAX_PARTICLES) particles.remove(0); // cull oldest
                float ang = rng.nextFloat() * (float) (2 * Math.PI);
                float speed = 1.6f + rng.nextFloat() * 2.2f;
                float upBias = 1.2f + rng.nextFloat() * 1.8f;
                particles.add(new Particle(m.x(), m.y(), m.z(),
                        (float) Math.cos(ang) * speed, upBias, (float) Math.sin(ang) * speed,
                        0f, PARTICLE_LIFE * (0.7f + rng.nextFloat() * 0.6f),
                        0.10f + rng.nextFloat() * 0.08f, m.resultTier()));
            }
        }
    }

    /** Advances every active effect and drops expired ones. */
    void update(float dt) {
        step(particles, dt);
        step(flashes, dt);
    }

    private static void step(List<Particle> list, float dt) {
        for (int i = list.size() - 1; i >= 0; i--) {
            Particle p = list.get(i).stepped(dt);
            if (p.expired()) list.remove(i);
            else list.set(i, p);
        }
    }

    /** Appends this frame's active effects as extra fruit-like spheres to render. */
    void appendTo(List<RtScene.FruitInstance> out) {
        for (Particle p : particles) {
            float r = p.baseRadius * p.sizeFrac();
            if (r > 0.008f) out.add(new RtScene.FruitInstance(p.x, p.y, p.z, r, p.tier));
        }
        for (Particle f : flashes) {
            // Flash grows to its peak size almost immediately then shrinks away —
            // the "pop" beat — rather than a plain linear fade.
            float t = f.age / f.life;
            float envelope = t < 0.25f ? t / 0.25f : 1f - (t - 0.25f) / 0.75f;
            float r = f.baseRadius * Math.max(0f, envelope);
            if (r > 0.02f) out.add(new RtScene.FruitInstance(f.x, f.y, f.z, r, f.tier));
        }
    }
}
