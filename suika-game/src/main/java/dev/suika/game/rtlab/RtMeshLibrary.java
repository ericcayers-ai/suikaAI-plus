package dev.suika.game.rtlab;

import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkQueue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR;
import static org.lwjgl.vulkan.VK12.*;

/**
 * Every mesh the RT scene uses (fruit sphere, table/wall quads, the glass jar's
 * cylinder side and base disc), packed into ONE shared vertex buffer and ONE shared
 * index buffer. The closest-hit shader receives per-instance {@code vertexOffset}/
 * {@code indexOffset} values and reads all meshes through the same two SSBO bindings —
 * per-mesh descriptor plumbing would otherwise multiply every binding by mesh count.
 *
 * <p>Vertex layout (32 bytes, two vec4s in std430): {@code (pos.xyz, u), (normal.xyz, v)}.
 * The BLAS build reads only the first 12 bytes of each vertex (position) with stride 32.
 * Indices are LOCAL to their mesh (each mesh's indices start at 0); the shader adds
 * the instance's vertexOffset.
 */
public final class RtMeshLibrary implements AutoCloseable {

    /** Offsets/counts of one mesh inside the shared buffers. Offsets are in ELEMENTS
     *  (vertices / uint indices), not bytes. */
    public record Mesh(int vertexOffset, int vertexCount, int indexOffset, int triangleCount) {}

    public static final int VERTEX_STRIDE_BYTES = 32;

    public final RtBuffer vertexBuffer, indexBuffer;
    public final Mesh sphere, tableQuad, wallQuad, cylinderSide, disc, jar;
    public final RtAccelerationStructure sphereBlas, tableBlas, wallBlas, cylinderBlas, discBlas, jarBlas;

    private static final int SPHERE_SUBDIVISIONS = 3;   // 1280 triangles
    private static final int CYLINDER_SEGMENTS   = 96;  // smooth enough for close-up glass

    public RtMeshLibrary(VkPhysicalDevice pd, VkDevice device, long commandPool, VkQueue queue) {
        List<float[]> vertices = new ArrayList<>(); // 8 floats each
        List<Integer> indices = new ArrayList<>();
        List<Mesh> meshes = new ArrayList<>();

        meshes.add(appendSphere(vertices, indices));
        meshes.add(appendTableQuad(vertices, indices));
        meshes.add(appendWallQuad(vertices, indices));
        meshes.add(appendCylinderSide(vertices, indices));
        meshes.add(appendDisc(vertices, indices));
        meshes.add(appendJar(vertices, indices));
        this.sphere = meshes.get(0);
        this.tableQuad = meshes.get(1);
        this.wallQuad = meshes.get(2);
        this.cylinderSide = meshes.get(3);
        this.disc = meshes.get(4);
        this.jar = meshes.get(5);

        int usage = VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
                | VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR;
        int hostVisible = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;

        this.vertexBuffer = new RtBuffer(pd, device, (long) vertices.size() * VERTEX_STRIDE_BYTES, usage, hostVisible);
        ByteBuffer vb = ByteBuffer.allocateDirect(vertices.size() * VERTEX_STRIDE_BYTES).order(ByteOrder.nativeOrder());
        for (float[] v : vertices) for (float f : v) vb.putFloat(f);
        vb.flip();
        vertexBuffer.uploadHostVisible(vb);

        this.indexBuffer = new RtBuffer(pd, device, (long) indices.size() * 4, usage, hostVisible);
        ByteBuffer ib = ByteBuffer.allocateDirect(indices.size() * 4).order(ByteOrder.nativeOrder());
        for (int i : indices) ib.putInt(i);
        ib.flip();
        indexBuffer.uploadHostVisible(ib);

        this.sphereBlas   = blas(pd, device, commandPool, queue, sphere);
        this.tableBlas    = blas(pd, device, commandPool, queue, tableQuad);
        this.wallBlas     = blas(pd, device, commandPool, queue, wallQuad);
        this.cylinderBlas = blas(pd, device, commandPool, queue, cylinderSide);
        this.discBlas     = blas(pd, device, commandPool, queue, disc);
        this.jarBlas      = blas(pd, device, commandPool, queue, jar);
    }

    private RtAccelerationStructure blas(VkPhysicalDevice pd, VkDevice device, long commandPool, VkQueue queue, Mesh m) {
        return RtAccelerationStructure.buildBlas(pd, device, commandPool, queue,
                vertexBuffer, (long) m.vertexOffset() * VERTEX_STRIDE_BYTES, m.vertexCount(), VERTEX_STRIDE_BYTES,
                indexBuffer, (long) m.indexOffset() * 4, m.triangleCount());
    }

    // ---- mesh generators (all unit-sized; instances scale/translate them) ----

    private static void vert(List<float[]> vs, float px, float py, float pz, float u,
                             float nx, float ny, float nz, float v) {
        vs.add(new float[]{px, py, pz, u, nx, ny, nz, v});
    }

    private static Mesh appendSphere(List<float[]> vs, List<Integer> is) {
        int v0 = vs.size(), i0 = is.size();
        Icosphere s = Icosphere.build(SPHERE_SUBDIVISIONS);
        int n = s.vertices.length / 3;
        for (int i = 0; i < n; i++) {
            float x = s.vertices[i * 3], y = s.vertices[i * 3 + 1], z = s.vertices[i * 3 + 2];
            // unit sphere: position == normal; UVs unused (fruit UVs are computed in
            // the shader from the object-space normal, avoiding a UV seam duplicate pass)
            vert(vs, x, y, z, 0f, x, y, z, 0f);
        }
        for (int idx : s.indices) is.add(idx);
        return new Mesh(v0, n, i0, s.indices.length / 3);
    }

    /** 1x1 quad in the XZ plane, centred at origin, facing +Y (the table top). */
    private static Mesh appendTableQuad(List<float[]> vs, List<Integer> is) {
        int v0 = vs.size(), i0 = is.size();
        vert(vs, -0.5f, 0f, -0.5f, 0f, 0f, 1f, 0f, 0f);
        vert(vs,  0.5f, 0f, -0.5f, 1f, 0f, 1f, 0f, 0f);
        vert(vs,  0.5f, 0f,  0.5f, 1f, 0f, 1f, 0f, 1f);
        vert(vs, -0.5f, 0f,  0.5f, 0f, 0f, 1f, 0f, 1f);
        quad(is, 0);
        return new Mesh(v0, 4, i0, 2);
    }

    /** 1x1 quad in the XY plane, centred at origin, facing +Z (the back wall). */
    private static Mesh appendWallQuad(List<float[]> vs, List<Integer> is) {
        int v0 = vs.size(), i0 = is.size();
        vert(vs, -0.5f, -0.5f, 0f, 0f, 0f, 0f, 1f, 0f);
        vert(vs,  0.5f, -0.5f, 0f, 1f, 0f, 0f, 1f, 0f);
        vert(vs,  0.5f,  0.5f, 0f, 1f, 0f, 0f, 1f, 1f);
        vert(vs, -0.5f,  0.5f, 0f, 0f, 0f, 0f, 1f, 1f);
        quad(is, 0);
        return new Mesh(v0, 4, i0, 2);
    }

    private static void quad(List<Integer> is, int base) {
        is.add(base); is.add(base + 1); is.add(base + 2);
        is.add(base); is.add(base + 2); is.add(base + 3);
    }

    /** Open-ended unit cylinder side: radius 1, y from 0 to 1, outward normals.
     *  No caps — the jar is open at the top and gets a separate base disc. */
    private static Mesh appendCylinderSide(List<float[]> vs, List<Integer> is) {
        int v0 = vs.size(), i0 = is.size();
        int segs = CYLINDER_SEGMENTS;
        for (int i = 0; i <= segs; i++) {
            float a = (float) (2 * Math.PI * i / segs);
            float x = (float) Math.cos(a), z = (float) Math.sin(a);
            float u = (float) i / segs;
            vert(vs, x, 0f, z, u, x, 0f, z, 0f);
            vert(vs, x, 1f, z, u, x, 0f, z, 1f);
        }
        for (int i = 0; i < segs; i++) {
            int b = i * 2;
            is.add(b); is.add(b + 1); is.add(b + 2);
            is.add(b + 2); is.add(b + 1); is.add(b + 3);
        }
        return new Mesh(v0, (segs + 1) * 2, i0, segs * 2);
    }

    /**
     * The mason jar as a surface of revolution, authored at WORLD scale (no
     * instance scaling): rounded bottom corner, straight body, curved shoulder,
     * two thread ribs on the neck, flared lip, and an inward rim ring at the top
     * so the mouth reads as a real open glass edge. The profile is anchored to
     * {@link JarShape}'s dimensions so the 3D physics containment and this render
     * mesh can never drift apart. Rendered two-sided (glass), separate base disc.
     */
    private static Mesh appendJar(List<float[]> vs, List<Integer> is) {
        float body = (float) JarShape.BODY_RADIUS;
        float bodyTop = (float) JarShape.BODY_TOP;
        float neck = (float) JarShape.NECK_RADIUS;
        float shoulderTop = (float) JarShape.SHOULDER_TOP;
        float mouthTop = (float) JarShape.MOUTH_TOP;
        float mouthIn = (float) JarShape.MOUTH_INNER_RADIUS;

        List<float[]> profile = new ArrayList<>();   // (radius, y) pairs, bottom -> rim
        // rounded bottom corner
        profile.add(new float[]{body - 0.85f, 0.02f});
        profile.add(new float[]{body - 0.25f, 0.15f});
        profile.add(new float[]{body - 0.04f, 0.55f});
        profile.add(new float[]{body, 1.2f});
        // straight body
        profile.add(new float[]{body, bodyTop});
        // shoulder: sample JarShape's own curve so physics and glass agree exactly
        for (int i = 1; i <= 6; i++) {
            double y = bodyTop + (shoulderTop - bodyTop) * i / 6.0;
            profile.add(new float[]{(float) JarShape.radiusAt(y), (float) y});
        }
        // neck with two thread ribs
        profile.add(new float[]{neck + 0.17f, 17.55f});
        profile.add(new float[]{neck, 17.85f});
        profile.add(new float[]{neck + 0.17f, 18.15f});
        profile.add(new float[]{neck, 18.45f});
        // flared lip, then the rim folds inward to the open mouth edge
        profile.add(new float[]{neck + 0.20f, 18.70f});
        profile.add(new float[]{neck + 0.20f, mouthTop - 0.05f});
        profile.add(new float[]{mouthIn, mouthTop});
        return appendLathe(vs, is, profile);
    }

    /** Revolves a (radius, y) profile around the Y axis with smooth normals
     *  computed from the profile's tangent (central differences). */
    private static Mesh appendLathe(List<float[]> vs, List<Integer> is, List<float[]> profile) {
        int v0 = vs.size(), i0 = is.size();
        int segs = CYLINDER_SEGMENTS;
        int rings = profile.size();

        for (int p = 0; p < rings; p++) {
            float r = profile.get(p)[0], y = profile.get(p)[1];
            // 2D outward normal of the profile polyline: perpendicular to the
            // tangent (dr, dy), oriented away from the axis.
            float[] prev = profile.get(Math.max(0, p - 1));
            float[] next = profile.get(Math.min(rings - 1, p + 1));
            float tr = next[0] - prev[0], ty = next[1] - prev[1];
            float len = (float) Math.sqrt(tr * tr + ty * ty);
            float nr = ty / len, ny = -tr / len;
            if (nr < 0) { nr = -nr; ny = -ny; }   // keep pointing outward

            float v = p / (float) (rings - 1);
            for (int i = 0; i <= segs; i++) {
                float a = (float) (2 * Math.PI * i / segs);
                float cos = (float) Math.cos(a), sin = (float) Math.sin(a);
                vert(vs, r * cos, y, r * sin, (float) i / segs, nr * cos, ny, nr * sin, v);
            }
        }
        int stride = segs + 1;
        for (int p = 0; p < rings - 1; p++) {
            for (int i = 0; i < segs; i++) {
                int b = p * stride + i;
                is.add(b); is.add(b + 1); is.add(b + stride);
                is.add(b + stride); is.add(b + 1); is.add(b + stride + 1);
            }
        }
        return new Mesh(v0, rings * stride, i0, (rings - 1) * segs * 2);
    }

    /** Unit disc in the XZ plane at y=0, facing +Y (the jar's glass base). */
    private static Mesh appendDisc(List<float[]> vs, List<Integer> is) {
        int v0 = vs.size(), i0 = is.size();
        int segs = CYLINDER_SEGMENTS;
        vert(vs, 0f, 0f, 0f, 0.5f, 0f, 1f, 0f, 0.5f);
        for (int i = 0; i <= segs; i++) {
            float a = (float) (2 * Math.PI * i / segs);
            float x = (float) Math.cos(a), z = (float) Math.sin(a);
            vert(vs, x, 0f, z, 0.5f + x * 0.5f, 0f, 1f, 0f, 0.5f + z * 0.5f);
        }
        for (int i = 0; i < segs; i++) {
            is.add(0); is.add(i + 2); is.add(i + 1);
        }
        return new Mesh(v0, segs + 2, i0, segs);
    }

    @Override
    public void close() {
        sphereBlas.close();
        tableBlas.close();
        wallBlas.close();
        cylinderBlas.close();
        discBlas.close();
        jarBlas.close();
        vertexBuffer.close();
        indexBuffer.close();
    }
}
