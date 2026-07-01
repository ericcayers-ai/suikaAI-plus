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
    public final Mesh sphere, tableQuad, wallQuad, cylinderSide, disc;
    public final RtAccelerationStructure sphereBlas, tableBlas, wallBlas, cylinderBlas, discBlas;

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
        this.sphere = meshes.get(0);
        this.tableQuad = meshes.get(1);
        this.wallQuad = meshes.get(2);
        this.cylinderSide = meshes.get(3);
        this.disc = meshes.get(4);

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
        vertexBuffer.close();
        indexBuffer.close();
    }
}
