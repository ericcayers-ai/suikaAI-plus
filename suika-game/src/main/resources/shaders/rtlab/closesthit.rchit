#version 460
#extension GL_EXT_ray_tracing : require
#extension GL_EXT_nonuniform_qualifier : enable

// Closest-hit — describes the surface the ray found, nothing more. All shading,
// shadow rays, and glass continuation happen back in raygen.rgen (see the
// portability note there: tracing from hit shaders needs recursion depth > 1,
// which some RT GPUs don't offer). This shader's job:
//   1. reconstruct the smooth world-space normal (+ two-sided flip),
//   2. compute UVs (vertex UVs for quads/cylinder; spherical UVs for fruit),
//   3. sample this instance's PBR maps (albedo / normal / roughness) — or fall
//      back to the procedural noise material for fruit tiers with no photo
//      texture — and
//   4. write it all into the HitInfo payload.

struct HitInfo {
    vec4 posT;        // xyz = world hit position, w = hit distance (miss: w < 0)
    vec4 normalRough; // xyz = shading normal, w = roughness 0..1
    vec4 albedoMat;   // rgb = linear albedo, w = material class
};
layout(location = 0) rayPayloadInEXT HitInfo hit;

hitAttributeEXT vec2 attribs;

const int MAT_FRUIT = 0;
const int MAT_WOOD  = 1;
const int MAT_WALL  = 2;
const int MAT_GLASS = 3;

// One record per TLAS instance, indexed by gl_InstanceCustomIndexEXT.
struct InstanceData {
    vec4  albedo;   // rgb base color (linear), w unused
    ivec4 tex;      // x = albedo map, y = normal map, z = roughness map (-1 = none), w = material class
    ivec4 mesh;     // x = index offset (uints), y = vertex offset (vertices)
    vec4  params;   // xy = UV tiling factors, z = base roughness, w = world size of one UV tile (for LOD)
};
layout(binding = 3, set = 0, std430) readonly buffer Instances { InstanceData inst[]; };

// Shared mesh data: every vertex is two vec4s — (pos.xyz, u) then (normal.xyz, v).
layout(binding = 4, set = 0, std430) readonly buffer Vertices { vec4 vdata[]; };
layout(binding = 5, set = 0, std430) readonly buffer Indices  { uint idx[]; };

layout(binding = 6, set = 0) uniform sampler2D textures[24];

// ---- procedural fallback material (fruit tiers with no bundled photo texture) ----
float hash1(vec3 p) {
    p = fract(p * 0.3183099 + vec3(0.1, 0.2, 0.3));
    p *= 17.0;
    return fract(p.x * p.y * p.z * (p.x + p.y + p.z));
}
float valueNoise3(vec3 p) {
    vec3 i = floor(p);
    vec3 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(
        mix(mix(hash1(i + vec3(0, 0, 0)), hash1(i + vec3(1, 0, 0)), f.x),
            mix(hash1(i + vec3(0, 1, 0)), hash1(i + vec3(1, 1, 0)), f.x), f.y),
        mix(mix(hash1(i + vec3(0, 0, 1)), hash1(i + vec3(1, 0, 1)), f.x),
            mix(hash1(i + vec3(0, 1, 1)), hash1(i + vec3(1, 1, 1)), f.x), f.y),
        f.z);
}
float fbm3(vec3 p) {
    return valueNoise3(p) * 0.6 + valueNoise3(p * 2.13) * 0.3 + valueNoise3(p * 4.29) * 0.1;
}

// Ray-tracing shaders have no derivatives, so implicit-LOD texture() is illegal —
// approximate the mip level from the ray-cone footprint at the hit instead.
float coneLod(int texIndex, float tileWorldSize, float ndv) {
    // Spread of one pixel per unit distance (vertical fov 50deg / image height,
    // baked as a constant — good enough for mip selection).
    float pixelSpread = 0.0012;
    float footprint = gl_HitTEXT * pixelSpread / max(ndv, 0.15);   // world units
    float texels = float(textureSize(textures[nonuniformEXT(texIndex)], 0).y);
    return clamp(log2(max(footprint * texels / max(tileWorldSize, 1e-4), 1e-6)), 0.0, 10.0);
}

void main() {
    InstanceData d = inst[gl_InstanceCustomIndexEXT];
    int matType = d.tex.w;

    uint tri = uint(gl_PrimitiveID);
    uint io = uint(d.mesh.x);
    uint vo = uint(d.mesh.y);
    uint i0 = idx[io + 3u * tri + 0u] + vo;
    uint i1 = idx[io + 3u * tri + 1u] + vo;
    uint i2 = idx[io + 3u * tri + 2u] + vo;

    vec4 p0 = vdata[2u * i0], e0 = vdata[2u * i0 + 1u];
    vec4 p1 = vdata[2u * i1], e1 = vdata[2u * i1 + 1u];
    vec4 p2 = vdata[2u * i2], e2 = vdata[2u * i2 + 1u];

    vec3 bary = vec3(1.0 - attribs.x - attribs.y, attribs.x, attribs.y);
    vec3 objNormal = normalize(e0.xyz * bary.x + e1.xyz * bary.y + e2.xyz * bary.z);
    vec2 vertUv = vec2(p0.w, e0.w) * bary.x + vec2(p1.w, e1.w) * bary.y + vec2(p2.w, e2.w) * bary.z;

    // Inverse-transpose for normals (handles the non-uniform scales used by the
    // table/wall quads and the jar cylinder, not just uniform fruit scaling).
    mat3 normalMatrix = transpose(mat3(gl_WorldToObjectEXT));
    vec3 N = normalize(normalMatrix * objNormal);

    // Two-sided: rays legitimately hit the far inside of the jar and the underside
    // of quads; flip so the normal always faces the incoming ray.
    vec3 rd = gl_WorldRayDirectionEXT;
    if (dot(N, rd) > 0.0) N = -N;

    vec3 P = gl_WorldRayOriginEXT + gl_HitTEXT * rd;
    float ndv = clamp(dot(N, -rd), 0.0, 1.0);

    vec3 albedo = d.albedo.rgb;
    float rough = d.params.z;

    if (matType == MAT_GLASS) {
        hit.posT = vec4(P, gl_HitTEXT);
        hit.normalRough = vec4(N, rough);
        hit.albedoMat = vec4(albedo, float(matType));
        return;
    }

    // ---- UVs + tangent frame ----
    vec2 texUv;
    vec3 T, B;
    if (matType == MAT_FRUIT) {
        // Spherical UVs straight from the object-space normal (unit sphere).
        texUv = vec2(atan(objNormal.z, objNormal.x) * 0.15915494309 + 0.5,
                     acos(clamp(objNormal.y, -1.0, 1.0)) * 0.31830988618);
        vec3 up = abs(objNormal.y) < 0.99 ? vec3(0, 1, 0) : vec3(1, 0, 0);
        T = normalize(normalMatrix * normalize(cross(up, objNormal)));
        B = normalize(cross(N, T));
    } else {
        // Quads/cylinder: real vertex UVs; tangents follow the U axis exactly
        // (table: +X carries U; wall: +X; cylinder unused for normal maps).
        texUv = vertUv * d.params.xy;
        T = normalize(normalMatrix * vec3(1, 0, 0));
        B = normalize(cross(N, T));
    }

    // ---- texture maps (or procedural fallback) ----
    if (d.tex.x >= 0) {
        float lod = coneLod(d.tex.x, d.params.w, ndv);
        vec3 texel = textureLod(textures[nonuniformEXT(d.tex.x)], texUv, lod).rgb;
        // Maps are stored UNORM; linearise the color map here (see RtTexture).
        albedo *= pow(texel, vec3(2.2));
    } else if (matType == MAT_FRUIT) {
        albedo *= 0.94 + 0.10 * fbm3(objNormal * 10.0 + 1.7);
    } else if (matType == MAT_WALL) {
        // Subtle plaster grain so the tan wall isn't a dead-flat fill.
        albedo *= 0.97 + 0.05 * fbm3(P * 1.7);
    }

    if (d.tex.y >= 0) {
        float lod = coneLod(d.tex.y, d.params.w, ndv);
        vec3 nm = textureLod(textures[nonuniformEXT(d.tex.y)], texUv, lod).rgb * 2.0 - 1.0;
        // Keep the perturbation modest — RT highlights amplify normal-map noise.
        N = normalize(T * nm.x * 0.6 + B * nm.y * 0.6 + N * max(nm.z, 0.35));
    } else if (matType == MAT_FRUIT) {
        // Procedural rind bump (original RT Lab fallback material).
        const float bumpFreq = 7.0, bumpEps = 0.12, bumpStrength = 0.05;
        vec3 samplePos = objNormal * bumpFreq;
        float h0 = fbm3(samplePos);
        float hT = fbm3(samplePos + T * bumpEps * bumpFreq);
        float hB = fbm3(samplePos + B * bumpEps * bumpFreq);
        N = normalize(N - (hT - h0) / bumpEps * bumpStrength * T
                        - (hB - h0) / bumpEps * bumpStrength * B);
    }

    if (d.tex.z >= 0) {
        float lod = coneLod(d.tex.z, d.params.w, ndv);
        rough = clamp(textureLod(textures[nonuniformEXT(d.tex.z)], texUv, lod).r, 0.03, 1.0);
    } else if (matType == MAT_FRUIT) {
        rough = clamp(rough + 0.25 * (fbm3(objNormal * 10.0 + 5.0) - 0.5), 0.05, 1.0);
    }

    hit.posT = vec4(P, gl_HitTEXT);
    hit.normalRough = vec4(N, rough);
    hit.albedoMat = vec4(albedo, float(matType));
}
