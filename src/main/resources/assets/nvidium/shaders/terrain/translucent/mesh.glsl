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


#ifdef TRANSLUCENCY_SORTING
vec3 depthPos = vec3(0);
#define SORTING_NETWORK_SIZE 32
#import <nvidium:sorting/sorting_network.glsl>
#endif

layout(local_size_x = 32) in;
layout(triangles, max_vertices=128, max_primitives=64) out;

//originAndBaseData.w is in quad count space, so is endIdx
taskNV in Task {
    vec4 originAndBaseData;
    uint quadCount;
    uint8_t jiggle;
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
    depthPos += exactPos;
    #endif
}

#ifdef TRANSLUCENCY_SORTING
void swapQuads(uint idxA, uint idxB) {
    if (idxA == idxB) {
        return;
    }

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
/*
shared Vertex vertexStore[32];
void exchangeVertex2x(uint baseOffset, uint vertex, bool swapA, bool swapB) {
    uint idA = (gl_LocalInvocationID.x<<1);
    uint idB = (gl_LocalInvocationID.x<<1)+1;
    vertexStore[idA] = terrainData[((baseOffset+idA)<<2)+vertex];
    vertexStore[idB] = terrainData[((baseOffset+idB)<<2)+vertex];
    barrier();
    memoryBarrierShared();
    if (swapA) {terrainData[((baseOffset+idA)<<2)+vertex] = vertexStore[uint(threadBufferIndex[idA])];}
    if (swapB) {terrainData[((baseOffset+idB)<<2)+vertex] = vertexStore[uint(threadBufferIndex[idB])];}
}

void executeNetwork() {
    //Net 0
    localSortA(0);
    //Net 1
    localSortA(1);
    localSortB(0);
    //Net 2
    localSortA(2);
    localSortB(1);
    localSortB(0);
    //Net 3
    localSortA(3);
    localSortB(2);
    localSortB(1);
    localSortB(0);
    //Net 4
    localSortA(4);
    localSortB(3);
    localSortB(2);
    localSortB(1);
    localSortB(0);
}*/


void performTranslucencySort() {
    uint basePtr = floatBitsToUint(originAndBaseData.w) + (gl_WorkGroupID.x<<5) - uint(jiggle);

    float depth = (abs(depthPos.x) + abs(depthPos.y) + abs(depthPos.z)) * (1/4f);
    putSortingData(uint8_t(gl_LocalInvocationID.x), depth);

    barrier();
    memoryBarrierShared();
    //TODO: use subgroup ballot to check if all the quads are already sorted, if they are dont perform sort op

    //Only use 16 threads to sort all 32 data
    if (gl_LocalInvocationID.x < 16) {
        uint idA = (gl_LocalInvocationID.x<<1);
        uint idB = (gl_LocalInvocationID.x<<1)+1;
        /*
        bool swapA = threadBufferFloat[idA] > 0.001f;
        bool swapB = threadBufferFloat[idB] > 0.001f;
        executeNetwork();
        swapA = swapA && (threadBufferFloat[idA] > 0.001f);
        swapB = swapB && (threadBufferFloat[idB] > 0.001f);

        //Perform the swapping of quads
        // THIS IS INCORRECT!, we musnt swap the quads

        exchangeVertex2x(basePtr, 0, swapA, swapB);
        exchangeVertex2x(basePtr, 1, swapA, swapB);
        exchangeVertex2x(basePtr, 2, swapA, swapB);
        exchangeVertex2x(basePtr, 3, swapA, swapB);
        */
        if (threadBufferFloat[idA] < threadBufferFloat[idB] && threadBufferFloat[idA] > 0.0001f && threadBufferFloat[idB] > 0.0001f) {
            swapQuads(idA + basePtr, idB + basePtr);
        }
    }
}
#endif

//TODO: extra per quad culling
void main() {

    if ((gl_GlobalInvocationID.x)>=quadCount) { //If its over the quad count, dont render
        putSortingData(uint8_t(gl_LocalInvocationID.x), -9999999999f);
        return;
    }
    //TODO:FIXME: the jiggling needs to be accounted for when emitting quads since otherwise it renders garbage data

    emitQuadIndicies();

    //Jiggle by offsetting the index by 1
    uint offsetFromBase = gl_GlobalInvocationID.x - uint(jiggle);

    //Each pair of meshlet invokations emits 4 vertices each and 2 primative each
    uint id = (floatBitsToUint(originAndBaseData.w) + offsetFromBase)<<2;

    #ifdef TRANSLUCENCY_SORTING
    //TODO: fixme: make faster and less hacky and not just do this
    if (gl_GlobalInvocationID.x < jiggle) {
        gl_MeshVerticesNV[(gl_LocalInvocationID.x<<2)+0].gl_Position = vec4(1,1,1,-1);
        gl_MeshVerticesNV[(gl_LocalInvocationID.x<<2)+1].gl_Position = vec4(1,1,1,-1);
        gl_MeshVerticesNV[(gl_LocalInvocationID.x<<2)+2].gl_Position = vec4(1,1,1,-1);
        gl_MeshVerticesNV[(gl_LocalInvocationID.x<<2)+3].gl_Position = vec4(1,1,1,-1);
    } else {
        emitVertex(id, 0);
        emitVertex(id, 1);
        emitVertex(id, 2);
        emitVertex(id, 3);
    }
    barrier();

    performTranslucencySort();
    int meta = 2;
    #else
    emitVertex(id, 0);
    emitVertex(id, 1);
    emitVertex(id, 2);
    emitVertex(id, 3);

    int meta = int(gl_GlobalInvocationID.x);
    #endif

    gl_MeshPrimitivesNV[(gl_LocalInvocationID.x<<1)].gl_PrimitiveID = meta;
    gl_MeshPrimitivesNV[(gl_LocalInvocationID.x<<1)|1].gl_PrimitiveID = meta;

    if (gl_LocalInvocationID.x == 0) {
        //Remaining quads in workgroup
        gl_PrimitiveCountNV = min(uint(int(quadCount)-int(gl_WorkGroupID.x<<5))<<1, 64);//2 primatives per quad
    }

}