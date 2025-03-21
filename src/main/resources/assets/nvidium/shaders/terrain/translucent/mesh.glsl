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


#ifdef TRANSLUCENCY_SORTING_QUADS
vec3 depthPos = vec3(0);
shared float depthBuffers[32];
#endif

layout(local_size_x = 32) in;
layout(triangles, max_vertices=128, max_primitives=64) out;

//originAndBaseData.w is in quad count space, so is endIdx
taskNV in Task {
    vec4 originAndBaseData;
    uint quadCount;
    #ifdef TRANSLUCENCY_SORTING_QUADS
    uint8_t jiggle;
    #endif
};

layout(location=1) out Interpolants {
#ifdef RENDER_FOG
    float16_t fogLerp;
#endif
    vec2 uv;
    vec3 v_colour;
} OUT[];

layout(binding = 1) uniform sampler2D tex_light;

vec4 sampleLight(vec2 uv) {
    //Its divided by 16 to match sodium/vanilla (it can never be 1 which is funny)
    return vec4(texture(tex_light, uv).rgb, 1);
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


    vec3 exactPos = pos+subchunkOffset.xyz;

    #ifdef RENDER_FOG
    float fogLerp = clamp(computeFogLerp(exactPos, isCylindricalFog, fogStart, fogEnd) * fogColour.a, 0, 1);
    OUT[outId].fogLerp = float16_t(fogLerp);
    #endif
    OUT[outId].uv = decodeVertexUV(V);

    vec4 tint = decodeVertexColour(V);
    tint *= sampleLight(decodeLightUV(V));
    tint *= tint.w;
    OUT[outId].v_colour = tint.rgb;

    #ifdef TRANSLUCENCY_SORTING_QUADS
    depthPos += exactPos;
    #endif

}

#ifdef TRANSLUCENCY_SORTING_QUADS
void swapQuads(uint idxA, uint idxB) {
    if (idxA == idxB) {
        return;
    }

    Vertex A0 = terrainData[(idxA<<2)+0];
    Vertex A1 = terrainData[(idxA<<2)+1];
    Vertex A2 = terrainData[(idxA<<2)+2];
    Vertex A3 = terrainData[(idxA<<2)+3];
    Vertex B0 = terrainData[(idxB<<2)+0];
    Vertex B1 = terrainData[(idxB<<2)+1];
    Vertex B2 = terrainData[(idxB<<2)+2];
    Vertex B3 = terrainData[(idxB<<2)+3];
    //groupMemoryBarrier();
    //memoryBarrier();
    //barrier();
    terrainData[(idxA<<2)+0] = B0;
    terrainData[(idxA<<2)+1] = B1;
    terrainData[(idxA<<2)+2] = B2;
    terrainData[(idxA<<2)+3] = B3;
    terrainData[(idxB<<2)+0] = A0;
    terrainData[(idxB<<2)+1] = A1;
    terrainData[(idxB<<2)+2] = A2;
    terrainData[(idxB<<2)+3] = A3;
    //groupMemoryBarrier();
    //memoryBarrier();
    //barrier();
}

void performTranslucencySort() {
    uint baseQuadPtr = floatBitsToUint(originAndBaseData.w) + (gl_WorkGroupID.x<<5);

    float depth = dot(depthPos, depthPos) * ((1/4f)*(1/4f));
    depthBuffers[gl_LocalInvocationID.x] = depth;

    if (gl_GlobalInvocationID.x < jiggle) {
        //If we are in the jiggle index dont attempt to swap else we start rendering garbage data
        depthBuffers[gl_LocalInvocationID.x] = -9999f;
    }

    groupMemoryBarrier();
    memoryBarrier();
    barrier();
    //TODO: use subgroup ballot to check if all the quads are already sorted, if they are dont perform sort op

    //Only use 16 threads to sort all 32 data
    if (gl_LocalInvocationID.x < 16) {
        uint idA = (gl_LocalInvocationID.x<<1);
        uint idB = (gl_LocalInvocationID.x<<1)+1;
        float a = depthBuffers[idA];
        float b = depthBuffers[idB];

        if (a > 0.0001f &&  b > 0.0001f && a < b) {
            swapQuads(idA + baseQuadPtr, idB + baseQuadPtr);
        }
    }
}
#endif

//TODO: extra per quad culling
void main() {
    #ifdef TRANSLUCENCY_SORTING_QUADS
    depthBuffers[gl_LocalInvocationID.x] = -99999999f;
    #endif
    if ((gl_GlobalInvocationID.x)>=quadCount) { //If its over the quad count, dont render
        return;
    }

    emitQuadIndicies();

    //Each pair of meshlet invokations emits 4 vertices each and 2 primative each
    uint id = (floatBitsToUint(originAndBaseData.w) + gl_GlobalInvocationID.x)<<2;

    #ifdef TRANSLUCENCY_SORTING_QUADS
    //If we are at the start, dont want to render as it contains garbled data (out of bounds)
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
    memoryBarrierShared();

    performTranslucencySort();
    #else
    emitVertex(id, 0);
    emitVertex(id, 1);
    emitVertex(id, 2);
    emitVertex(id, 3);
    #endif

    gl_MeshPrimitivesNV[(gl_LocalInvocationID.x<<1)].gl_PrimitiveID = int((id>>2)<<4)|(0<<3);
    gl_MeshPrimitivesNV[(gl_LocalInvocationID.x<<1)|1].gl_PrimitiveID = int((id>>2)<<4)|(1<<3);

    if (gl_LocalInvocationID.x == 0) {
        //Remaining quads in workgroup
        gl_PrimitiveCountNV = min(uint(int(quadCount)-int(gl_WorkGroupID.x<<5))<<1, 64);//2 primatives per quad
    }

}