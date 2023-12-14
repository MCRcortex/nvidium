#version 460

#extension GL_ARB_shading_language_include : enable
#pragma optionNV(unroll all)
#define UNROLL_LOOP
#extension GL_NV_mesh_shader : require
#extension GL_NV_gpu_shader5 : require
#extension GL_NV_bindless_texture : require

#extension GL_KHR_shader_subgroup_basic : require
#extension GL_KHR_shader_subgroup_ballot : require
#extension GL_KHR_shader_subgroup_vote : require

#import <nvidium:occlusion/scene.glsl>
#import <nvidium:terrain/fog.glsl>
#import <nvidium:terrain/vertex_format.glsl>


//It seems like for terrain at least, the sweat spot is ~16 quads per mesh invocation (even if the local size is not 32 )
layout(local_size_x = 32) in;
layout(triangles, max_vertices=64, max_primitives=32) out;

layout(location=1) out Interpolants {
    f16vec2 uv;
    f16vec3 tint;
    f16vec3 addin;
} OUT[];

layout(location=5) perprimitiveNV out PerPrimData {
    int8_t lodBias;
    uint8_t alphaCutoff;
} per_prim_out[];

taskNV in Task {
    vec3 origin;
    uint baseOffset;
    uint quadCount;

    //Binary search indexs and data
    uvec4 binIa;
    uvec4 binIb;
    uvec4 binVa;
    uvec4 binVb;
};

layout(binding = 1) uniform sampler2D tex_light;

vec4 sampleLight(uvec2 uv) {
    return vec4(texelFetch(tex_light, ivec2(uv), 0).rgb, 1);
}


void emitQuadIndicies(uint outVertexBase, uint sideOffset) {
    uint primBase = gl_LocalInvocationID.x * 3;
    gl_PrimitiveIndicesNV[primBase + 0] = outVertexBase + ((0 + sideOffset) & 3);
    gl_PrimitiveIndicesNV[primBase + 1] = outVertexBase + ((1 + sideOffset) & 3);
    gl_PrimitiveIndicesNV[primBase + 2] = outVertexBase + ((2 + sideOffset) & 3);
}

void emitVertex(uint quadVertexBase, uint outVertexBase, uint innerId) {
    Vertex V = terrainData[quadVertexBase + innerId];

    uint outId = outVertexBase + innerId;
    vec3 pos = decodeVertexPosition(V)+origin;
    gl_MeshVerticesNV[outId].gl_Position = MVP*vec4(pos,1.0);

    //TODO: make this shared state between all the vertices?
    float mippingBias = decodeVertexMippingBias(V);
    float alphaCutoff = decodeVertexAlphaCutoff(V);

    OUT[outId].uv = f16vec2(decodeVertexUV(V));

    vec4 tint = decodeVertexColour(V);
    tint *= sampleLight(decodeLightUV(V));
    tint *= tint.w;

    vec3 tintO;
    vec3 addiO;
    computeFog(isCylindricalFog, pos+subchunkOffset.xyz, tint, fogColour, fogStart, fogEnd, tintO, addiO);
    OUT[outId].tint = f16vec3(tintO);
    OUT[outId].addin = f16vec3(addiO);
}

void emitPerPrimativeData(uint vertexBaseId) {
    Vertex V = terrainData[vertexBaseId];
    int8_t lodBias = int8_t(clamp(decodeVertexMippingBias(V) * 16, -128, 127));
    uint8_t alphaCutoff = uint8_t(decodeVertexAlphaCutoff(V) * 255);
    per_prim_out[gl_LocalInvocationID.x].lodBias = lodBias;
    per_prim_out[gl_LocalInvocationID.x].alphaCutoff = alphaCutoff;
}


//Do a binary search via global invocation index to determine the base offset
// Note, all threads in the work group are probably going to take the same path
uint getOffset() {
    uint gii = gl_GlobalInvocationID.x >> 1;

    //TODO: replace this with binary search
    if (gii < binIa.x) {
        return binVa.x + gii + baseOffset;
    } else if (gii < binIa.y) {
        return binVa.y + (gii - binIa.x) + baseOffset;
    } else if (gii < binIa.z) {
        return binVa.z + (gii - binIa.y) + baseOffset;
    } else if (gii < binIa.w) {
        return binVa.w + (gii - binIa.z) + baseOffset;
    } else if (gii < binIb.x) {
        return binVb.x + (gii - binIa.w) + baseOffset;
    } else if (gii < binIb.y) {
        return binVb.y + (gii - binIb.x) + baseOffset;
    } else if (gii < binIb.z) {
        return binVb.z + (gii - binIb.y) + baseOffset;
    } else if (gii < binIb.w) {
        return binVb.w + (gii - binIb.z) + baseOffset;
    } else {
        return uint(-1);
    }
}

void main() {
    uint id = getOffset();

    //If its over, dont render
    if (id == uint(-1)) {
        return;
    }
    uint quadVertexBase = id << 2;
    uint outVertexBase = (gl_LocalInvocationID.x & uint(-2)) << 1;
    uint sideOffset = (gl_LocalInvocationID.x & 1) * 2;
    emitQuadIndicies(outVertexBase, sideOffset);
    emitVertex(quadVertexBase, outVertexBase, sideOffset);
    emitVertex(quadVertexBase, outVertexBase, sideOffset + 1);
    emitPerPrimativeData(quadVertexBase);

    if (gl_LocalInvocationID.x == 0) {
        //Remaining quads in workgroup
        gl_PrimitiveCountNV = min(uint(int(quadCount)-int(gl_WorkGroupID.x<<4))<<1, 32);//2 primatives per quad
    }
}