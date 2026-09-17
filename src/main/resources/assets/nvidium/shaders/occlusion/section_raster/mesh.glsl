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

#define ADD_SIZE (0.1f)
layout(local_size_x = 32) in;
layout(triangles, max_vertices=32, max_primitives=48) out;

taskNV in Task {
    uint32_t _visOutBase;//Base output visibility index
    uint32_t _offset;
    mat4 regionTransform;
    ivec3 chunkShift;
    uint sectionCount;
};

const uint PILUTA[] = {0, 3, 6, 0, 1, 7, 4, 5};
const uint PILUTB[] = {1, 2, 6, 4, 0, 7, 6, 4};
const uint PILUTC[] = {2, 0, 4, 5, 1, 3, 7, 2};
const uint PILUTD[] = {1, 2, 0, 5, 5, 1, 7, 7};

const uint PILUTE[] = {6, 2, 3, 7};

void emitIndicies(uint batchIdx, int visIndex) {
    uint tid = gl_LocalInvocationID.x % 8;
    uint baseIdx = batchIdx * 36;
    uint baseVtx = batchIdx * 8;
    gl_PrimitiveIndicesNV[baseIdx + ((tid<<2)|0)] = PILUTA[tid] + baseVtx;
    gl_PrimitiveIndicesNV[baseIdx + ((tid<<2)|1)] = PILUTB[tid] + baseVtx;
    gl_PrimitiveIndicesNV[baseIdx + ((tid<<2)|2)] = PILUTC[tid] + baseVtx;
    gl_PrimitiveIndicesNV[baseIdx + ((tid<<2)|3)] = PILUTD[tid] + baseVtx;
    gl_MeshPrimitivesNV[batchIdx * 12 + tid].gl_PrimitiveID = visIndex;
}
void emitParital(uint batchIdx, int visIndex) {
    uint tid = gl_LocalInvocationID.x % 8;
    uint baseIdx = batchIdx * 36;
    uint baseVtx = batchIdx * 8;
    gl_PrimitiveIndicesNV[baseIdx + 32 + tid] = PILUTE[tid] + baseVtx;
    gl_MeshPrimitivesNV[batchIdx * 12 + 8 + tid].gl_PrimitiveID = visIndex;
}

//TODO: Check if the section can be culled via fog
void main() {
    uint baseIdx = gl_WorkGroupID.x * 4;
    if (baseIdx >= sectionCount) {
        if (gl_LocalInvocationID.x == 0) gl_PrimitiveCountNV = 0;
        return;
    }

    uint batchIdx = gl_LocalInvocationID.x / 8;
    uint tid = gl_LocalInvocationID.x % 8;
    uint currentSectionIdx = baseIdx + batchIdx;
    
    if (currentSectionIdx >= sectionCount) {
        if (tid == 0) sectionVisibility[int(_visOutBase|currentSectionIdx)] = uint8_t(0);
        gl_MeshVerticesNV[gl_LocalInvocationID.x].gl_Position = vec4(0.0);
        emitIndicies(batchIdx, 0);
        if (tid < 4) emitParital(batchIdx, 0);
        if (gl_LocalInvocationID.x == 0) gl_PrimitiveCountNV = 48;
        return;
    }

    int visibilityIndex = int(_visOutBase|currentSectionIdx);

    uint8_t lastData = sectionVisibility[visibilityIndex];
    // this is almost 100% guarenteed not needed afaik
    //barrier();

    ivec4 header = sectionData[_offset|currentSectionIdx].header;
    //If the section header was empty or the hide section bit is set, return

    //NOTE: technically this has the infinitly small probability of not rendering a block if the block is located at
    // 0,0,0 the only block in the chunk and the first thing in the buffer
    // to fix, also check that the ranges are null
    if (sectionEmpty(header) || (header.y&(1<<17)) != 0) {
        if (tid == 0) {
            sectionVisibility[visibilityIndex] = uint8_t(0);
        }
        gl_MeshVerticesNV[gl_LocalInvocationID.x].gl_Position = vec4(0.0);
        emitIndicies(batchIdx, visibilityIndex);
        if (tid < 4) emitParital(batchIdx, visibilityIndex);
        if (gl_LocalInvocationID.x == 0) gl_PrimitiveCountNV = 48;
        return;
    }

    vec3 mins = (header.xyz&0xF)-ADD_SIZE;
    vec3 maxs = mins+((header.xyz>>4)&0xF)+1+(ADD_SIZE*2);
    ivec3 chunk = ivec3(header.xyz)>>8;
    chunk.y &= 0x1ff;
    chunk.y <<= 32-9;
    chunk.y >>= 32-9;

    ivec3 relativeChunkPos = (chunk + chunkShift);
    vec3 corner = vec3(relativeChunkPos<<4);
    vec3 cornerCopy = corner;

    //TODO: try mix instead or something other than just ternaries, i think they get compiled to a cmov type instruction but not sure
    corner += vec3(((tid&1)==0)?mins.x:maxs.x, ((tid&4)==0)?mins.y:maxs.y, ((tid&2)==0)?mins.z:maxs.z);
    gl_MeshVerticesNV[gl_LocalInvocationID.x].gl_Position = (MVP*(regionTransform*vec4(corner, 1.0)));

    int prim_payload = (visibilityIndex<<8)|int(((uint(lastData))<<1)&0xff)|1;

    emitIndicies(batchIdx, prim_payload);
    if (tid < 4) {
        emitParital(batchIdx, prim_payload);
    }
    if (tid == 0) {
        cornerCopy += subchunkOffset.xyz;
        vec3 minPos = mins + cornerCopy;
        vec3 maxPos = maxs + cornerCopy;
        bool isInSection = all(lessThan(minPos, vec3(ADD_SIZE))) && all(lessThan(vec3(-ADD_SIZE), maxPos));

        //Shift and set, this gives us a bonus of having the last 8 frames as visibility history
        sectionVisibility[visibilityIndex] = uint8_t(lastData<<1) | uint8_t(isInSection?1:0);//Inject visibility aswell
        //sectionVisibility[visibilityIndex] = uint8_t(lastData<<1) | uint8_t(0);
    }
    if (gl_LocalInvocationID.x == 0) {
        gl_PrimitiveCountNV = 48;
    }
}