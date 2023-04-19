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

layout(local_size_x = 32) in;
layout(triangles, max_vertices=64, max_primitives=32) out;

//originAndBaseData.w is in quad count space, so is endIdx
taskNV in Task {
    vec4 originAndBaseData;
    uint quadCount;
};

layout(location=1) out Interpolants {
    vec4 tint;
    vec3 uv_bias;
} OUT[];


vec3 decodeVertex(Vertex v) {
    return vec3(v.a,v.b,v.c)*(32.0f/65535)-8.0f;
}

layout(binding = 1) uniform sampler2D tex_light;

vec4 sampleLight(vec2 uv) {
    return texture(tex_light, clamp(uv / 256.0, vec2(0.5 / 16.0), vec2(15.5 / 16.0)));
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
    gl_MeshVerticesNV[(gl_LocalInvocationID.x<<1)].gl_Position   = MVP*vec4(decodeVertex(A)+originAndBaseData.xyz,1.0);
    gl_MeshVerticesNV[(gl_LocalInvocationID.x<<1)|1].gl_Position = MVP*vec4(decodeVertex(B)+originAndBaseData.xyz,1.0);
    //TODO: see if ternary or array is faster
    bool isA = (gl_LocalInvocationID.x&1)==0;
    gl_PrimitiveIndicesNV[primId]   = (isA?0:2)+idxBase;
    gl_PrimitiveIndicesNV[primId+1] = (isA?1:3)+idxBase;
    gl_PrimitiveIndicesNV[primId+2] = (isA?2:0)+idxBase;

    uint material = MATERIAL_OVERRIDE;
    OUT[(gl_LocalInvocationID.x<<1)|0].uv_bias = vec3(vec2(A.g,A.h)/65535, (material&1u)!=0u?0.0f:-4.0f);//Temporary untill sodium 0.5
    OUT[(gl_LocalInvocationID.x<<1)|1].uv_bias = vec3(vec2(B.g,B.h)/65535, (material&1u)!=0u?0.0f:-4.0f);//Temporary untill sodium 0.5


    vec4 tintA = vec4(A.e&int16_t(0xFF),(A.e>>8)&int16_t(0xFF),A.f&int16_t(0xFF),(A.f>>8)&int16_t(0xFF))/255;
    vec4 tintB = vec4(B.e&int16_t(0xFF),(B.e>>8)&int16_t(0xFF),B.f&int16_t(0xFF),(B.f>>8)&int16_t(0xFF))/255;
    tintA *= sampleLight(vec2(int16_t(A.i),int16_t(A.j)));
    tintB *= sampleLight(vec2(int16_t(B.i),int16_t(B.j)));
    OUT[(gl_LocalInvocationID.x<<1)|0].tint = tintA;
    OUT[(gl_LocalInvocationID.x<<1)|1].tint = tintB;

    gl_MeshPrimitivesNV[gl_LocalInvocationID.x].gl_PrimitiveID = int(gl_GlobalInvocationID.x>>1);

    if (gl_LocalInvocationID.x == 0) {
        //Remaining quads in workgroup
        gl_PrimitiveCountNV = min(uint(int(quadCount)-int(gl_WorkGroupID.x<<4))<<1, 32);//2 primatives per quad
    }
}