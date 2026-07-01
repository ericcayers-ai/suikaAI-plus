#version 460
#extension GL_EXT_ray_tracing : require

// Shadow-ray miss: the light-direction ray reached the miss shader without hitting
// any geometry first, i.e. nothing occludes the light from this point — fully lit.
// (raygen initializes this payload to 0.0/occluded before tracing, so a hit that
// skips both shaders — VK_RAY_FLAGS_SKIP_CLOSEST_HIT_SHADER_KHR — just leaves it
// at 0.0, and only this miss shader flips it to "visible".)

layout(location = 1) rayPayloadInEXT float shadowVisibility;

void main() {
    shadowVisibility = 1.0;
}
