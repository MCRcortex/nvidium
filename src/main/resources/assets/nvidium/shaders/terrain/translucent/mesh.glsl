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
layout(triangles, max_vertices=128, max_primitives=64) out;

//originAndBaseData.w is in quad count space, so is endIdx
taskNV in Task {
    vec4 originAndBaseData;
    uint quadCount;
};

layout(location=1) out Interpolants {
    f16vec3 tint;
    f16vec3 addin;
    f16vec2 uv;
} OUT[];

layout(binding = 1) uniform sampler2D tex_light;

vec4 sampleLight(uvec2 uv) {
    return vec4(texelFetch(tex_light, ivec2(uv), 0).rgb, 1);
}

void emitQuadIndicies() {
    uint primBase = gl_LocalInvocationID.x * 6;
    uint vertexBase = gl_LocalInvocationID.x<<2;
    gl_PrimitiveIndicesNV[primBase+0] = vertexBase+0;
    gl_PrimitiveIndicesNV[primBase+1] = vertexBase+1;
    gl_PrimitiveIndicesNV[primBase+2] = vertexBase+2;
    gl_PrimitiveIndicesNV[primBase+3] = vertexBase+2;
    gl_PrimitiveIndicesNV[primBase+4] = vertexBase+3;
    gl_PrimitiveIndicesNV[primBase+5] = vertexBase+0;
}

#ifdef TRANSLUCENCY_SORTING
float depth = 0;
shared float depthArray[32];
#endif

void emitVertex(uint vertexBaseId, uint innerId) {
    Vertex V = terrainData[vertexBaseId + innerId];
    uint outId = (gl_LocalInvocationID.x<<2)+innerId;
    vec3 pos = decodeVertexPosition(V)+originAndBaseData.xyz;
    gl_MeshVerticesNV[outId].gl_Position = MVP*vec4(pos,1.0);
    OUT[outId].uv = f16vec2(decodeVertexUV(V));

    vec4 tint = decodeVertexColour(V);
    tint *= sampleLight(decodeLightUV(V));
    tint *= tint.w;

    vec3 tintO;
    vec3 addiO;
    vec3 exactPos = pos+subchunkOffset.xyz;
    computeFog(isCylindricalFog, exactPos, tint, fogColour, fogStart, fogEnd, tintO, addiO);
    OUT[outId].tint = f16vec3(tintO);
    OUT[outId].addin = f16vec3(addiO);

    #ifdef TRANSLUCENCY_SORTING
    depth += abs(exactPos.x);
    depth += abs(exactPos.y);
    depth += abs(exactPos.z);
    #endif
}

//TODO: extra per quad culling
void main() {

    //TODO: add jiggle to sort different indicies to make it sort cross boundaries this jiggle needs to be on the source index offset

    if ((gl_GlobalInvocationID.x)>=quadCount) { //If its over the quad count, dont render
        return;
    }
    emitQuadIndicies();

    //Each pair of meshlet invokations emits 4 vertices each and 2 primative each
    uint id = (floatBitsToUint(originAndBaseData.w) + gl_GlobalInvocationID.x)<<2;

    emitVertex(id, 0);
    emitVertex(id, 1);
    emitVertex(id, 2);
    emitVertex(id, 3);

    #ifdef TRANSLUCENCY_SORTING
    depth *= 1/4f;

    depthArray[gl_LocalInvocationID.x] = depth;
    barrier();
    memoryBarrierShared();
    int meta = 0;
    if (gl_LocalInvocationID.x < 16) {
        uint idxA = (gl_LocalInvocationID.x<<1)+1;
        uint idxB = (gl_LocalInvocationID.x<<1);
        bool shouldSwap = depthArray[idxA]<depthArray[idxB];
        //Convert to global indexing
        idxA = idxA + (gl_WorkGroupID.x<<5);
        idxB = idxB + (gl_WorkGroupID.x<<5);
        if (shouldSwap && (idxB<quadCount)) {
            idxA += floatBitsToUint(originAndBaseData.w);
            idxB += floatBitsToUint(originAndBaseData.w);
            Vertex A0 = terrainData[(idxA<<2)+0];
            Vertex A1 = terrainData[(idxA<<2)+1];
            Vertex A2 = terrainData[(idxA<<2)+2];
            Vertex A3 = terrainData[(idxA<<2)+3];
            terrainData[(idxA<<2)+0] = terrainData[(idxB<<2)+0];
            terrainData[(idxA<<2)+1] = terrainData[(idxB<<2)+1];
            terrainData[(idxA<<2)+2] = terrainData[(idxB<<2)+2];
            terrainData[(idxA<<2)+3] = terrainData[(idxB<<2)+3];
            terrainData[(idxB<<2)+0] = A0;
            terrainData[(idxB<<2)+1] = A1;
            terrainData[(idxB<<2)+2] = A2;
            terrainData[(idxB<<2)+3] = A3;
        }

        meta = int(shouldSwap);
    }

    #else
    int meta = int(gl_GlobalInvocationID.x);
    #endif

    gl_MeshPrimitivesNV[(gl_LocalInvocationID.x<<1)].gl_PrimitiveID = meta;
    gl_MeshPrimitivesNV[(gl_LocalInvocationID.x<<1)|1].gl_PrimitiveID = meta;

    if (gl_LocalInvocationID.x == 0) {
        //Remaining quads in workgroup
        gl_PrimitiveCountNV = min(uint(int(quadCount)-int(gl_WorkGroupID.x<<5))<<1, 64);//2 primatives per quad
    }
}