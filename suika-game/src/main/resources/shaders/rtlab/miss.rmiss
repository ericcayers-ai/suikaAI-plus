#version 460
#extension GL_EXT_ray_tracing : require

// Primary-ray miss: mark the payload as "no hit". The environment color itself is
// evaluated in raygen (envColor()) so the same bright-studio function also feeds
// the glass reflection approximation — one definition, no drift between the two.

struct HitInfo {
    vec4 posT;
    vec4 normalRough;
    vec4 albedoMat;
};
layout(location = 0) rayPayloadInEXT HitInfo hit;

void main() {
    hit.posT = vec4(0.0, 0.0, 0.0, -1.0);
}
