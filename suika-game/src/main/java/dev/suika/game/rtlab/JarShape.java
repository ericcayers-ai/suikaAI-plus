package dev.suika.game.rtlab;

/**
 * The mason jar's dimensions and radial profile — one source of truth shared by
 * the render mesh ({@link RtMeshLibrary}'s lathe), the 3D physics containment
 * ({@link Jar3DPhysics}), and the drop-through-the-mouth clamping in both game
 * sessions. Deliberately pure math (no Vulkan/GLFW imports) so physics and its
 * tests never touch GPU classes.
 *
 * <p>Modelled on a wide-mouth mason jar (see the reference photos): straight
 * cylindrical body, curved shoulder pulling in, short threaded neck with two
 * ribs, flared lip, and a genuinely OPEN mouth — fruits enter the jar only
 * through that opening, so drops are clamped to the mouth radius, not the body
 * radius.
 */
public final class JarShape {

    private JarShape() {}

    /** Outer glass radius of the straight body section. */
    public static final double BODY_RADIUS = 5.45;
    /** Top of the straight body — equals the 2D engine's CONTAINER_HEIGHT so
     *  classic-2D gameplay never interacts with the taper above it. */
    public static final double BODY_TOP = 15.0;
    /** Top of the shoulder curve / base of the neck. */
    public static final double SHOULDER_TOP = 17.2;
    /** Glass radius of the neck (between shoulder and lip). */
    public static final double NECK_RADIUS = 4.35;
    /** Y of the rim — the top of the jar; everything above is open air. */
    public static final double MOUTH_TOP = 19.0;
    /** Inner radius of the opening at the rim (inside the lip). */
    public static final double MOUTH_INNER_RADIUS = 4.15;

    /** Clearance kept between fruit surface and glass when clamping drops, so a
     *  fruit aimed at the very edge of the mouth still visibly clears the rim. */
    public static final double MOUTH_MARGIN = 0.10;

    /** Glass surface radius at height {@code y} (the containment boundary for 3D
     *  physics). Below the body top it's the straight wall; through the shoulder
     *  it eases into the neck with a cosine blend (matches the render profile's
     *  visual curve closely enough that fruit never pokes through the shoulder);
     *  above the shoulder it's the neck. */
    public static double radiusAt(double y) {
        if (y <= BODY_TOP) return BODY_RADIUS;
        if (y >= SHOULDER_TOP) return NECK_RADIUS;
        double t = (y - BODY_TOP) / (SHOULDER_TOP - BODY_TOP);
        double ease = 0.5 - 0.5 * Math.cos(t * Math.PI);   // smooth in AND out
        return BODY_RADIUS + (NECK_RADIUS - BODY_RADIUS) * ease;
    }

    /** Max distance a fruit CENTER of the given radius may sit from the jar axis
     *  when dropped through the mouth. Collapses to the axis if the fruit is too
     *  big for the opening (can't happen with droppable tiers: dekopon r=1.27
     *  vs. mouth 4.15). */
    public static double dropLimit(double fruitRadius) {
        return Math.max(0.0, MOUTH_INNER_RADIUS - MOUTH_MARGIN - fruitRadius);
    }
}
