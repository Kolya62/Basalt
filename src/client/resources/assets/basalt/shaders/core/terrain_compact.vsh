#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:fog.glsl>
#include <minecraft:globals.glsl>
#include <minecraft:projection.glsl>
#include <minecraft:sample_lightmap.glsl>
#include <minecraft:terrainglobals.glsl>
#ifndef MULTIDRAW_TERRAIN
    #include <minecraft:chunksection.glsl>
#endif

// Basalt compact terrain vertex (16 bytes instead of vanilla's 28):
//   PackedPos  RGBA16_UINT : xyz = (pos + 8) * 2048, w = block light | sky light << 8
//   Color      RGBA8_UNORM
//   UV0        RG16_UNORM
layout(location = 0) in uvec4 PackedPos;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;
#ifdef MULTIDRAW_TERRAIN
layout(location = 3) in ivec3 ChunkPosition;
layout(location = 4) in float ChunkVisibility;
#endif

#ifndef OIT_ALPHA_ONLY
uniform sampler2D Sampler2;
#endif

layout(location = 0) out float sphericalVertexDistance;
layout(location = 1) out float cylindricalVertexDistance;
layout(location = 2) out vec4 vertexColor;
layout(location = 3) out vec2 texCoord0;
layout(location = 4) out float chunkVisibility;

void main() {
    vec3 Position = vec3(PackedPos.xyz) * (1.0 / 2048.0) - 8.0;
    ivec2 UV2 = ivec2(int(PackedPos.w & 0xFFu), int(PackedPos.w >> 8));
    vec3 pos = Position + (ChunkPosition - CameraBlockPos) + CameraOffset;
    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);

    sphericalVertexDistance = fog_spherical_distance(pos);
    cylindricalVertexDistance = fog_cylindrical_distance(pos);
    #ifndef OIT_ALPHA_ONLY
    vertexColor = Color * sample_lightmap(Sampler2, UV2);
    #else
    vertexColor = Color;
    #endif
    texCoord0 = UV0;

    const float chunkFullyVisibleRange = 16.0;
    float dist = length(pos);
    chunkVisibility = mix(1.0, ChunkVisibility, clamp((dist - chunkFullyVisibleRange) / chunkFullyVisibleRange, 0.0, 1.0));
}
