package dev.suika.game.rtlab;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/** Icosphere is pure math (no GPU/Vulkan dependency), so unlike the rest of rtlab
 *  it's actually testable in CI — this exists to catch mesh-generation regressions
 *  that would otherwise only surface as visual artifacts in the RT Lab window. */
class IcosphereTest {

    private static Icosphere build(int subdivisions) throws Exception {
        Method m = Icosphere.class.getDeclaredMethod("build", int.class);
        m.setAccessible(true);
        return (Icosphere) m.invoke(null, subdivisions);
    }

    @Test
    void vertexAndTriangleCountsMatchTheStandardIcosphereFormula() throws Exception {
        // Subdividing an icosahedron n times: 20*4^n triangles, 10*4^n+2 vertices
        // (Euler's formula V-E+F=2 with E=30*4^n) — verified against RtTraceTest's
        // actual logged output (subdivisions=3 -> 642 verts, 1280 tris) before this
        // test existed, so this pins down real, already-observed-correct behaviour.
        for (int n = 0; n <= 4; n++) {
            Icosphere sphere = build(n);
            int expectedTriangles = 20 * (int) Math.pow(4, n);
            int expectedVertices = 10 * (int) Math.pow(4, n) + 2;
            assertEquals(expectedTriangles, sphere.indices.length / 3, "triangle count at subdivision " + n);
            assertEquals(expectedVertices, sphere.vertices.length / 3, "vertex count at subdivision " + n);
        }
    }

    @Test
    void everyVertexLiesOnTheUnitSphere() throws Exception {
        Icosphere sphere = build(3);
        int vertexCount = sphere.vertices.length / 3;
        for (int i = 0; i < vertexCount; i++) {
            float x = sphere.vertices[i * 3], y = sphere.vertices[i * 3 + 1], z = sphere.vertices[i * 3 + 2];
            double len = Math.sqrt(x * x + y * y + z * z);
            assertEquals(1.0, len, 1e-5, "vertex " + i + " should be unit length (it doubles as its own normal)");
        }
    }

    @Test
    void everyIndexReferencesARealVertex() throws Exception {
        Icosphere sphere = build(3);
        int vertexCount = sphere.vertices.length / 3;
        for (int idx : sphere.indices) {
            assertTrue(idx >= 0 && idx < vertexCount, "index " + idx + " out of range [0, " + vertexCount + ")");
        }
    }

    @Test
    void noDegenerateTriangles() throws Exception {
        Icosphere sphere = build(2);
        int triCount = sphere.indices.length / 3;
        for (int t = 0; t < triCount; t++) {
            int i0 = sphere.indices[t * 3], i1 = sphere.indices[t * 3 + 1], i2 = sphere.indices[t * 3 + 2];
            assertNotEquals(i0, i1, "triangle " + t + " has a repeated vertex");
            assertNotEquals(i1, i2, "triangle " + t + " has a repeated vertex");
            assertNotEquals(i0, i2, "triangle " + t + " has a repeated vertex");
        }
    }
}
