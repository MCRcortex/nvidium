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

//originAndBaseData.w is in quad count space, so is endIdx
taskNV in Task {
    vec4 originAndBaseData;
    uint quadCount;
};

layout(location=1) out Interpolants {
    vec4 tint;
    vec4 addin;
    vec2 uv;
} OUT[];

layout(binding = 1) uniform sampler2D tex_light;

vec4 sampleLight(uvec2 uv) {
    return vec4(texelFetch(tex_light, ivec2(uv), 0).rgb, 1);
}

//TODO: extra per quad culling
void main() {
    if ((gl_GlobalInvocationID.x>>1)>=quadCount) { //If its over the quad count, dont render
        return;
    }
    //Each pair of meshlet invokations emits 2 vertices each and 1 primative each
    uint id = (floatBitsToUint(originAndBaseData.w)<<2) + (gl_GlobalInvocationID.x<<1);//mul by 2 since there are 2 threads per quad each thread needs to process 2 vertices

    Vertex A = terrainData[id];
    Vertex B = terrainData[id|1];

    //TODO: OPTIMIZE
    uint primId = gl_LocalInvocationID.x*3;
    uint idxBase = (gl_LocalInvocationID.x>>1)<<2;
    vec3 posA = decodeVertexPosition(A)+originAndBaseData.xyz;
    vec3 posB = decodeVertexPosition(B)+originAndBaseData.xyz;
    gl_MeshVerticesNV[(gl_LocalInvocationID.x<<1)].gl_Position   = MVP*vec4(posA,1.0);
    gl_MeshVerticesNV[(gl_LocalInvocationID.x<<1)|1].gl_Position = MVP*vec4(posB,1.0);
    //TODO: see if ternary or array is faster
    bool isA = (gl_LocalInvocationID.x&1)==0;
    gl_PrimitiveIndicesNV[primId]   = (isA?0:2)+idxBase;
    gl_PrimitiveIndicesNV[primId+1] = (isA?1:3)+idxBase;
    gl_PrimitiveIndicesNV[primId+2] = (isA?2:0)+idxBase;

    OUT[(gl_LocalInvocationID.x<<1)|0].uv = decodeVertexUV(A);
    OUT[(gl_LocalInvocationID.x<<1)|1].uv = decodeVertexUV(B);

    vec4 tintA = decodeVertexColour(A);
    vec4 tintB = decodeVertexColour(B);
    tintA *= sampleLight(decodeLightUV(A));
    tintA *= tintA.w;
    tintB *= sampleLight(decodeLightUV(B));
    tintB *= tintB.w;

    vec4 tintAO;
    vec4 addiAO;
    vec4 tintBO;
    vec4 addiBO;
    computeFog(isCylindricalFog, posA+subchunkOffset.xyz, tintA, fogColour, fogStart, fogEnd, tintAO, addiAO);
    computeFog(isCylindricalFog, posB+subchunkOffset.xyz, tintB, fogColour, fogStart, fogEnd, tintBO, addiBO);
    OUT[(gl_LocalInvocationID.x<<1)|0].tint = tintAO;
    OUT[(gl_LocalInvocationID.x<<1)|0].addin = addiAO;
    OUT[(gl_LocalInvocationID.x<<1)|1].tint = tintBO;
    OUT[(gl_LocalInvocationID.x<<1)|1].addin = addiBO;

    gl_MeshPrimitivesNV[gl_LocalInvocationID.x].gl_PrimitiveID = int(gl_GlobalInvocationID.x>>1);

    if (gl_LocalInvocationID.x == 0) {
        //Remaining quads in workgroup
        gl_PrimitiveCountNV = min(uint(int(quadCount)-int(gl_WorkGroupID.x<<4))<<1, 32);//2 primatives per quad
    }
}