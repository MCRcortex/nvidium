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

layout(local_size_x = 32) in;
layout(triangles, max_vertices=64, max_primitives=32) out;

layout(location=1) out Interpolants {
    vec4 tint;
    vec4 addin;
    vec4 uv_bias_cutoff;
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

vec3 decodeVertex(Vertex v) {
    return vec3(v.a,v.b,v.c)*(32.0f/65535)-8.0f;
}

layout(binding = 1) uniform sampler2D tex_light;

vec4 sampleLight(vec2 uv) {
    return texture(tex_light, clamp(uv / 256.0, vec2(0.5 / 16.0), vec2(15.5 / 16.0)));
}

void processVertPair(uint id) {

    Vertex A = terrainData[id];
    Vertex B = terrainData[id|1];

    //TODO: OPTIMIZE
    uint primId = gl_LocalInvocationID.x*3;
    uint idxBase = (gl_LocalInvocationID.x>>1)<<2;
    vec3 posA = decodeVertex(A)+origin;
    vec3 posB = decodeVertex(B)+origin;
    gl_MeshVerticesNV[(gl_LocalInvocationID.x<<1)].gl_Position   = MVP*vec4(posA,1.0);
    gl_MeshVerticesNV[(gl_LocalInvocationID.x<<1)|1].gl_Position = MVP*vec4(posB,1.0);

    bool isA = (gl_LocalInvocationID.x&1)==0;
    gl_PrimitiveIndicesNV[primId]   = (isA?0:2)+idxBase;
    gl_PrimitiveIndicesNV[primId+1] = (isA?1:3)+idxBase;
    gl_PrimitiveIndicesNV[primId+2] = (isA?2:0)+idxBase;

    bool hasMipping = (A.d&int16_t(4))!=int16_t(0);
    float alphaCutoff = (float[](0.0f, 0.1f,0.5f))[(A.d&int16_t(3))];

    OUT[(gl_LocalInvocationID.x<<1)|0].uv_bias_cutoff = vec4(vec2(A.g,A.h)*(1f/65536), hasMipping?0.0f:-8.0f, alphaCutoff);
    OUT[(gl_LocalInvocationID.x<<1)|1].uv_bias_cutoff = vec4(vec2(B.g,B.h)*(1f/65536), hasMipping?0.0f:-8.0f, alphaCutoff);


    vec4 tintA = vec4(A.e&int16_t(0xFF),(A.e>>8)&int16_t(0xFF),A.f&int16_t(0xFF),(A.f>>8)&int16_t(0xFF))/255;
    vec4 tintB = vec4(B.e&int16_t(0xFF),(B.e>>8)&int16_t(0xFF),B.f&int16_t(0xFF),(B.f>>8)&int16_t(0xFF))/255;
    tintA *= sampleLight(vec2(int16_t(A.i),int16_t(A.j)));
    tintA *= tintA.w;
    tintB *= sampleLight(vec2(int16_t(B.i),int16_t(B.j)));
    tintB *= tintB.w;
    vec4 tintAO;
    vec4 addiAO;
    vec4 tintBO;
    vec4 addiBO;
    computeFog(isSphericalFog, posA+subchunkOffset.xyz, tintA, fogColour, fogStart, fogEnd, tintAO, addiAO);
    computeFog(isSphericalFog, posB+subchunkOffset.xyz, tintB, fogColour, fogStart, fogEnd, tintBO, addiBO);
    OUT[(gl_LocalInvocationID.x<<1)|0].tint = tintAO;
    OUT[(gl_LocalInvocationID.x<<1)|0].addin = addiAO;
    OUT[(gl_LocalInvocationID.x<<1)|1].tint = tintBO;
    OUT[(gl_LocalInvocationID.x<<1)|1].addin = addiBO;

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