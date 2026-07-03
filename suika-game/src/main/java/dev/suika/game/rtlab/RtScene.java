package dev.suika.game.rtlab;

import com.badlogic.gdx.graphics.Color;
import dev.suika.core.FruitTier;
import dev.suika.game.FruitColors;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkQueue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_GEOMETRY_INSTANCE_TRIANGLE_FACING_CULL_DISABLE_BIT_KHR;
import static org.lwjgl.vulkan.VK12.*;

/**
 * The RT game world: a pine-wood table, a brightly lit tan wall behind it, a
 * cylindrical glass jar standing on the table, and the live game's fruits inside
 * the jar. Owns the per-frame instance-data SSBO, camera UBO, and TLAS rebuild;
 * static geometry/BLASes live in {@link RtMeshLibrary} and photo textures in
 * {@link RtTextureSet}.
 *
 * <p>World layout (game units, matching the 2D engine's scale): the jar's axis is
 * the world Y axis; the table top is y=0; the jar interior spans y 0..15 with
 * radius {@link #JAR_RADIUS}. The 2D game's x∈[0,10] plane maps to world x∈[-5,5]
 * at z=0. The camera looks at the jar from +Z with the wall far behind at
 * z={@link #WALL_Z} — beyond the focal plane, so it lands in bokeh.
 */
public final class RtScene implements AutoCloseable {

    /** One sphere to draw (a fruit in the jar, the hover fruit, or the next-up chip). */
    public record FruitInstance(float x, float y, float z, float radius, FruitTier tier) {}

    // ---- world layout ----
    /** Jar dimensions live in {@link JarShape} (shared with physics); kept here as
     *  a convenience alias for callers that only need the body radius. */
    public static final float JAR_RADIUS = (float) JarShape.BODY_RADIUS;
    /** Far enough behind the jar that the orbit camera can never reach it: max zoom
     *  radius is {@code RtLabLauncher.ZOOM_MAX} (40), so a full 180° orbit puts the
     *  camera at z=-40 — still 18 units in front of the wall. (Was -10, which the
     *  orbiting camera flew straight through.) */
    public static final float WALL_Z     = -58.0f;
    // Table centred under the jar and large enough that every orbit angle still sees
    // wood under the camera; wall sized up to keep filling the frame from its new,
    // much farther position.
    public static final float TABLE_SIZE_X = 120f, TABLE_SIZE_Z = 120f, TABLE_CENTER_Z = 0f;
    public static final float WALL_SIZE_X = 160f, WALL_SIZE_Y = 90f;

    // The movable metal drop chute (the "drop cursor" of the reference scene):
    // an open steel tube hovering above the jar mouth, following the pointer.
    // Sized to snugly HUG the largest droppable tier (PERSIMMON, radius 1.66 — see
    // FruitTier) rather than swim loosely around it: just enough clearance that the
    // fruit doesn't clip the wall, no more. Taller than before too, so more of it
    // runs out of frame at the top (was already partly off-screen; now emphatically
    // so) instead of reading as a short stubby collar.
    public static final float CHUTE_RADIUS = 1.82f;
    public static final float CHUTE_BOTTOM_Y = 19.6f;   // just above JarShape.MOUTH_TOP
    public static final float CHUTE_HEIGHT = 7.0f;      // top runs well out of frame

    /** Cull masks (must match raygen.rgen): shadow rays skip MASK_GLASS. */
    private static final int MASK_SOLID = 0x01, MASK_GLASS = 0x02;

    private static final int MAT_FRUIT = 0, MAT_WOOD = 1, MAT_WALL = 2, MAT_GLASS = 3, MAT_METAL = 4;

    private static final int INSTANCE_DATA_BYTES = 64;  // struct InstanceData in closesthit.rchit
    private static final int TLAS_INSTANCE_BYTES = 64;  // sizeof(VkAccelerationStructureInstanceKHR)

    private final VkPhysicalDevice physicalDevice;
    private final VkDevice device;
    private final long commandPool;
    private final VkQueue queue;
    private final RtMeshLibrary meshes;
    private final RtTextureSet textureSet;

    private RtBuffer instanceBuffer;      // TLAS input
    private RtBuffer instanceDataBuffer;  // shader-visible per-instance records
    public RtAccelerationStructure tlas;
    public final RtBuffer cameraUbo;

    // position/forward/right/up (4 vec4s) + 8 floats (tanHalfFov, time, aperture,
    // focusDist, blend, aspect, 2 pad) = 96 bytes, a 16-byte multiple as std140 needs.
    private static final int CAMERA_UBO_SIZE = 4 * 4 * 4 + 8 * 4;

    public RtScene(VkPhysicalDevice physicalDevice, VkDevice device, long commandPool, VkQueue queue,
                   RtMeshLibrary meshes, RtTextureSet textureSet) {
        this.physicalDevice = physicalDevice;
        this.device = device;
        this.commandPool = commandPool;
        this.queue = queue;
        this.meshes = meshes;
        this.textureSet = textureSet;
        this.cameraUbo = new RtBuffer(physicalDevice, device, CAMERA_UBO_SIZE,
                VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
    }

    /** Camera parameters for one frame (thin-lens model — see raygen.rgen). */
    public record CameraFrame(float posX, float posY, float posZ,
                              float fwdX, float fwdY, float fwdZ,
                              float rightX, float rightY, float rightZ,
                              float upX, float upY, float upZ,
                              float tanHalfFov, float time, float aperture,
                              float focusDist, float blend, float aspect) {}

    /** Rebuilds the per-frame instance buffers and the TLAS from the given fruit
     *  snapshot plus the fixed environment, then updates the descriptor set.
     *  The chute follows the player's aim ({@code chuteX}/{@code chuteZ}) and is
     *  hidden after game over ({@code chuteVisible}). */
    public void updateFrame(RtPipeline pipeline, RtOutputImage output, List<FruitInstance> fruits,
                            float chuteX, float chuteZ, boolean chuteVisible, CameraFrame cam) {
        int envCount = 4 + (chuteVisible ? 1 : 0);
        int n = envCount + fruits.size();
        int hostVisible = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;

        ByteBuffer data = ByteBuffer.allocateDirect(n * INSTANCE_DATA_BYTES).order(ByteOrder.nativeOrder());
        ByteBuffer tlasInstances = ByteBuffer.allocateDirect(n * TLAS_INSTANCE_BYTES).order(ByteOrder.nativeOrder());
        int index = 0;

        // ---- environment ----
        // 0: table — pine wood PBR triple, one texture tile every 8 world units.
        writeInstanceData(data, 1f, 1f, 1f,
                textureSet.woodBase() + RtTextureSet.MAP_ALBEDO,
                textureSet.woodBase() + RtTextureSet.MAP_NORMAL,
                textureSet.woodBase() + RtTextureSet.MAP_ROUGHNESS,
                MAT_WOOD, meshes.tableQuad,
                TABLE_SIZE_X / 8f, TABLE_SIZE_Z / 8f, 0.7f, 8f);
        writeTlasInstance(tlasInstances, meshes.tableBlas.deviceAddress(), index++, MASK_SOLID,
                TABLE_SIZE_X, 1f, TABLE_SIZE_Z, 0f, 0f, TABLE_CENTER_Z);

        // 1: tan wall — procedural plaster (brightly lit; see raygen's softbox hotspot).
        writeInstanceData(data, srgb(0.80f), srgb(0.70f), srgb(0.56f),
                -1, -1, -1, MAT_WALL, meshes.wallQuad, 1f, 1f, 0.88f, 10f);
        writeTlasInstance(tlasInstances, meshes.wallBlas.deviceAddress(), index++, MASK_SOLID,
                WALL_SIZE_X, WALL_SIZE_Y, 1f, 0f, WALL_SIZE_Y / 2f, WALL_Z);

        // 2: the mason jar — lathe mesh authored at world scale (body, shoulder,
        //    threaded neck, open mouth; see RtMeshLibrary.appendJar / JarShape).
        writeInstanceData(data, 1f, 1f, 1f, -1, -1, -1, MAT_GLASS, meshes.jar, 1f, 1f, 0.04f, 1f);
        writeTlasInstance(tlasInstances, meshes.jarBlas.deviceAddress(), index++, MASK_GLASS,
                1f, 1f, 1f, 0f, 0f, 0f);

        // 3: jar base — glass disc just above the table so it doesn't z-fight the wood.
        writeInstanceData(data, 1f, 1f, 1f, -1, -1, -1, MAT_GLASS, meshes.disc, 1f, 1f, 0.04f, 1f);
        writeTlasInstance(tlasInstances, meshes.discBlas.deviceAddress(), index++, MASK_GLASS,
                JAR_RADIUS - 0.1f, 1f, JAR_RADIUS - 0.1f, 0f, 0.02f, 0f);

        // 4 (optional): the metal drop chute, tracking the aim point above the mouth.
        // Base roughness 0.62 — clearly brushed steel; closesthit.rchit additionally
        // modulates it with fine circumferential streak noise (the brushed-line grain)
        // and raygen's MAT_METAL branch broadens/softens the highlight accordingly.
        if (chuteVisible) {
            writeInstanceData(data, 0.62f, 0.63f, 0.66f, -1, -1, -1, MAT_METAL,
                    meshes.cylinderSide, 1f, 1f, 0.62f, 1f);
            writeTlasInstance(tlasInstances, meshes.cylinderBlas.deviceAddress(), index++, MASK_SOLID,
                    CHUTE_RADIUS, CHUTE_HEIGHT, CHUTE_RADIUS, chuteX, CHUTE_BOTTOM_Y, chuteZ);
        }

        // ---- fruits ----
        Color tmp = new Color();
        for (FruitInstance f : fruits) {
            int base = textureSet.fruitBase(f.tier());
            tmp.set(FruitColors.of(f.tier()));
            float r, g, b;
            if (base >= 0) {
                // Photo-textured tiers: the albedo map carries the color; a light tier
                // tint keeps distant fruit recognisable by hue at a glance.
                r = 0.85f + 0.15f * srgb(tmp.r); g = 0.85f + 0.15f * srgb(tmp.g); b = 0.85f + 0.15f * srgb(tmp.b);
            } else {
                r = srgb(tmp.r); g = srgb(tmp.g); b = srgb(tmp.b);
            }
            // Roughness raised from 0.45 to 0.62 — less glossy/plastic-looking, reads
            // more like real fruit skin under the studio softbox.
            writeInstanceData(data, r, g, b,
                    base >= 0 ? base + RtTextureSet.MAP_ALBEDO : -1,
                    base >= 0 ? base + RtTextureSet.MAP_NORMAL : -1,
                    base >= 0 ? base + RtTextureSet.MAP_ROUGHNESS : -1,
                    MAT_FRUIT, meshes.sphere, 1f, 1f, 0.62f, (float) (f.radius() * Math.PI));
            writeTlasInstance(tlasInstances, meshes.sphereBlas.deviceAddress(), index++, MASK_SOLID,
                    f.radius(), f.radius(), f.radius(), f.x(), f.y(), f.z());
        }

        data.flip();
        tlasInstances.flip();

        // Build this frame's resources FIRST, destroy last frame's after: the TLAS
        // build below runs through OneShotCommands (vkQueueWaitIdle), which is also
        // what guarantees the previous frame's trace — still using the OLD TLAS and
        // buffers — has finished before we free them.
        RtBuffer oldData = instanceDataBuffer, oldInstances = instanceBuffer;
        RtAccelerationStructure oldTlas = tlas;

        instanceDataBuffer = new RtBuffer(physicalDevice, device, data.remaining(),
                VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, hostVisible);
        instanceDataBuffer.uploadHostVisible(data);

        instanceBuffer = new RtBuffer(physicalDevice, device, tlasInstances.remaining(),
                VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT | VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR,
                hostVisible);
        instanceBuffer.uploadHostVisible(tlasInstances);

        tlas = RtAccelerationStructure.buildTlas(physicalDevice, device, commandPool, queue, instanceBuffer, n);

        if (oldTlas != null) oldTlas.close();
        if (oldInstances != null) oldInstances.close();
        if (oldData != null) oldData.close();

        ByteBuffer camBuf = ByteBuffer.allocateDirect(CAMERA_UBO_SIZE).order(ByteOrder.nativeOrder());
        camBuf.putFloat(cam.posX()).putFloat(cam.posY()).putFloat(cam.posZ()).putFloat(0f);
        camBuf.putFloat(cam.fwdX()).putFloat(cam.fwdY()).putFloat(cam.fwdZ()).putFloat(0f);
        camBuf.putFloat(cam.rightX()).putFloat(cam.rightY()).putFloat(cam.rightZ()).putFloat(0f);
        camBuf.putFloat(cam.upX()).putFloat(cam.upY()).putFloat(cam.upZ()).putFloat(0f);
        camBuf.putFloat(cam.tanHalfFov()).putFloat(cam.time()).putFloat(cam.aperture()).putFloat(cam.focusDist());
        camBuf.putFloat(cam.blend()).putFloat(cam.aspect()).putFloat(0f).putFloat(0f);
        camBuf.flip();
        cameraUbo.uploadHostVisible(camBuf);

        pipeline.updateDescriptorSet(tlas.handle, output.view, cameraUbo, instanceDataBuffer,
                meshes.vertexBuffer, meshes.indexBuffer);
    }

    /** sRGB -> linear (the shader works in linear; FruitColors are authored in sRGB). */
    private static float srgb(float c) {
        return (float) Math.pow(c, 2.2);
    }

    /** One InstanceData record — layout must match closesthit.rchit exactly. */
    private static void writeInstanceData(ByteBuffer buf, float r, float g, float b,
                                          int albedoTex, int normalTex, int roughTex, int matType,
                                          RtMeshLibrary.Mesh mesh,
                                          float uvScaleU, float uvScaleV, float roughnessBase, float uvTileWorldSize) {
        buf.putFloat(r).putFloat(g).putFloat(b).putFloat(1f);                      // vec4 albedo
        buf.putInt(albedoTex).putInt(normalTex).putInt(roughTex).putInt(matType);  // ivec4 tex
        buf.putInt(mesh.indexOffset()).putInt(mesh.vertexOffset()).putInt(0).putInt(0); // ivec4 mesh
        buf.putFloat(uvScaleU).putFloat(uvScaleV).putFloat(roughnessBase).putFloat(uvTileWorldSize); // vec4 params
    }

    /** Packs one VkAccelerationStructureInstanceKHR: row-major 3x4 transform with
     *  per-axis scale + translation, custom index (-> InstanceData lookup), the cull
     *  mask, SBT offset 0, and the BLAS address. */
    private static void writeTlasInstance(ByteBuffer buf, long blasAddress, int customIndex, int mask,
                                          float sx, float sy, float sz, float x, float y, float z) {
        buf.putFloat(sx).putFloat(0f).putFloat(0f).putFloat(x);
        buf.putFloat(0f).putFloat(sy).putFloat(0f).putFloat(y);
        buf.putFloat(0f).putFloat(0f).putFloat(sz).putFloat(z);
        buf.putInt((customIndex & 0xFFFFFF) | ((mask & 0xFF) << 24));
        buf.putInt(VK_GEOMETRY_INSTANCE_TRIANGLE_FACING_CULL_DISABLE_BIT_KHR << 24);
        buf.putLong(blasAddress);
    }

    @Override
    public void close() {
        if (tlas != null) tlas.close();
        if (instanceBuffer != null) instanceBuffer.close();
        if (instanceDataBuffer != null) instanceDataBuffer.close();
        cameraUbo.close();
    }
}
