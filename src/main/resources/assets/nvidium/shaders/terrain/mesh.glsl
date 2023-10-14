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

layout(local_size_x = 32) in;
layout(triangles, max_vertices=64, max_primitives=32) out;

layout(location=1) out Interpolants {
    f16vec4 uv_bias_cutoff;
    f16vec3 tint;
    f16vec3 addin;
} OUT[];

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

void processVertPair(uint id) {

    Vertex A = terrainData[id];
    Vertex B = terrainData[id|1];

    //TODO: OPTIMIZE
    uint primId = gl_LocalInvocationID.x*3;
    uint idxBase = (gl_LocalInvocationID.x>>1)<<2;
    vec3 posA = decodeVertexPosition(A)+origin;
    vec3 posB = decodeVertexPosition(B)+origin;
    gl_MeshVerticesNV[(gl_LocalInvocationID.x<<1)].gl_Position   = MVP*vec4(posA,1.0);
    gl_MeshVerticesNV[(gl_LocalInvocationID.x<<1)|1].gl_Position = MVP*vec4(posB,1.0);

    bool isA = (gl_LocalInvocationID.x&1)==0;
    gl_PrimitiveIndicesNV[primId]   = (isA?0:2)+idxBase;
    gl_PrimitiveIndicesNV[primId+1] = (isA?1:3)+idxBase;
    gl_PrimitiveIndicesNV[primId+2] = (isA?2:0)+idxBase;

    float mippingBias = decodeVertexMippingBias(A);
    float alphaCutoff = decodeVertexAlphaCutoff(A);

    OUT[(gl_LocalInvocationID.x<<1)|0].uv_bias_cutoff = f16vec4(vec4(decodeVertexUV(A), mippingBias, alphaCutoff));
    OUT[(gl_LocalInvocationID.x<<1)|1].uv_bias_cutoff = f16vec4(vec4(decodeVertexUV(B), mippingBias, alphaCutoff));


    vec4 tintA = decodeVertexColour(A);
    vec4 tintB = decodeVertexColour(B);
    tintA *= sampleLight(decodeLightUV(A));
    tintA *= tintA.w;
    tintB *= sampleLight(decodeLightUV(B));
    tintB *= tintB.w;

    vec3 tintAO;
    vec3 addiAO;
    vec3 tintBO;
    vec3 addiBO;

    //TODO: MOVE FOG TO FRAGMENT SHADER (its computed a heckin lot less than in the vertex shdaer, so should help alot)
    // in reducing computational complexity
    computeFog(isCylindricalFog, posA+subchunkOffset.xyz, tintA, fogColour, fogStart, fogEnd, tintAO, addiAO);
    computeFog(isCylindricalFog, posB+subchunkOffset.xyz, tintB, fogColour, fogStart, fogEnd, tintBO, addiBO);
    OUT[(gl_LocalInvocationID.x<<1)|0].tint = f16vec3(tintAO);
    OUT[(gl_LocalInvocationID.x<<1)|0].addin = f16vec3(addiAO);
    OUT[(gl_LocalInvocationID.x<<1)|1].tint = f16vec3(tintBO);
    OUT[(gl_LocalInvocationID.x<<1)|1].addin = f16vec3(addiBO);

    gl_MeshPrimitivesNV[gl_LocalInvocationID.x].gl_PrimitiveID = int(gl_GlobalInvocationID.x>>1);
}



//Do a binary search via global invocation index to determine the base offset
// Note, all threads in the work group are probably going to take the same path
uint getOffset() {
    uint gii = gl_GlobalInvocationID.x>>1;

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

    processVertPair(((id << 1) | (gl_LocalInvocationID.x&1)) << 1);

    if (gl_LocalInvocationID.x == 0) {
        //Remaining quads in workgroup
        gl_PrimitiveCountNV = min(uint(int(quadCount)-int(gl_WorkGroupID.x<<4))<<1, 32);//2 primatives per quad
    }
}