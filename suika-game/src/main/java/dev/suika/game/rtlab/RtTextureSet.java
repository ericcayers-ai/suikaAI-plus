package dev.suika.game.rtlab;

import dev.suika.core.FruitTier;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkQueue;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Loads every bundled PBR texture the RT scene uses and hands out array indices for
 * the pipeline's texture array binding. Order is fixed: wood table first (indices
 * 0..2), then each photo-textured fruit tier's albedo/normal/roughness triple.
 * Tiers without a bundled photo texture return -1 and use the procedural material
 * in closesthit.rchit (see textures/fruit/LICENSE-TEXTURES.txt for the mapping).
 */
public final class RtTextureSet implements AutoCloseable {

    /** albedo, normal, roughness — every material triple follows this order. */
    public static final int MAP_ALBEDO = 0, MAP_NORMAL = 1, MAP_ROUGHNESS = 2;

    private final List<RtTexture> textures = new ArrayList<>();
    private final Map<FruitTier, Integer> fruitBase = new EnumMap<>(FruitTier.class);
    private final int woodBase;

    public RtTextureSet(VkPhysicalDevice pd, VkDevice device, long commandPool, VkQueue queue) {
        this.woodBase = loadTriple(pd, device, commandPool, queue, "/textures/environment/table_wood");
        record FruitTex(FruitTier tier, String dir) {}
        FruitTex[] mapped = {
                new FruitTex(FruitTier.STRAWBERRY, "strawberry"),
                new FruitTex(FruitTier.GRAPE,      "grape"),
                new FruitTex(FruitTier.DEKOPON,    "dekopon"),
                new FruitTex(FruitTier.APPLE,      "apple"),
                new FruitTex(FruitTier.WATERMELON, "watermelon"),
        };
        for (FruitTex ft : mapped) {
            fruitBase.put(ft.tier(), loadTriple(pd, device, commandPool, queue, "/textures/fruit/" + ft.dir()));
        }
        if (textures.size() > RtPipeline.MAX_TEXTURES) {
            throw new IllegalStateException("Texture count " + textures.size()
                    + " exceeds the pipeline's fixed array size " + RtPipeline.MAX_TEXTURES);
        }
    }

    private int loadTriple(VkPhysicalDevice pd, VkDevice device, long commandPool, VkQueue queue, String dir) {
        int base = textures.size();
        textures.add(new RtTexture(pd, device, commandPool, queue, dir + "/albedo.jpg"));
        textures.add(new RtTexture(pd, device, commandPool, queue, dir + "/normal.png"));
        textures.add(new RtTexture(pd, device, commandPool, queue, dir + "/roughness.jpg"));
        return base;
    }

    public List<RtTexture> all() { return textures; }

    /** Base index of the wood table triple (albedo at base, +1 normal, +2 roughness). */
    public int woodBase() { return woodBase; }

    /** Base index of this tier's texture triple, or -1 for procedural-material tiers. */
    public int fruitBase(FruitTier tier) { return fruitBase.getOrDefault(tier, -1); }

    @Override
    public void close() {
        for (RtTexture t : textures) t.close();
    }
}
