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

#define MESH_WORKLOAD_PER_INVOCATION 16

//This is 1 since each task shader workgroup -> multiple meshlets. its not each globalInvocation (afaik)
layout(local_size_x=1) in;

bool shouldRenderVisible(uint sectionId) {
    return (sectionVisibility[sectionId]&uint8_t(1)) != uint8_t(0);
}

//In here add an array that is then "logged" on in the mesh shader to find the draw data
taskNV out Task {
    vec3 origin;
    uint baseOffset;
    uint quadCount;

    //Binary search indexs and data
    uvec4 binIa;
    uvec4 binIb;
    uvec4 binVa;
    uvec4 binVb;
};

void putBinData(inout uint idx, inout uint lastIndex, uint offset, uint nextOffset) {
    uint len = nextOffset - offset;
    uint id = idx++;
    if (id < 4) {
        binIa[id] = lastIndex + len;
        binVa[id] = offset;
    } else {
        binIb[id - 4] = lastIndex + len;
        binVb[id - 4] = offset;
    }
    lastIndex += len;
}

//Populate the tasks with respect to the chunk face visibility
void populateTasks(ivec3 relChunkPos, uvec4 ranges) {
    //TODO: make the ranges cumulate up, this means that we can fit much much more data per chunk
    // as the range will be spred across all the offsets since they are not the absolute offset
    uint idx = 0;
    uint lastIndex = 0;

    binIa = uvec4(0);
    binIb = uvec4(0);

    if (relChunkPos.y <= 0) {
        putBinData(idx, lastIndex, 0, ranges.x&0xFFFF);
    }
    if (relChunkPos.y >= 0) {
        putBinData(idx, lastIndex, ranges.x&0xFFFF, (ranges.x>>16)&0xFFFF);
    }

    if (relChunkPos.x <= 0) {
        putBinData(idx, lastIndex, (ranges.x>>16)&0xFFFF, ranges.y&0xFFFF);
    }
    if (relChunkPos.x >= 0) {
        putBinData(idx, lastIndex, ranges.y&0xFFFF, (ranges.y>>16)&0xFFFF);
    }

    if (relChunkPos.z <= 0) {
        putBinData(idx, lastIndex, (ranges.y>>16)&0xFFFF, ranges.z&0xFFFF);
    }
    if (relChunkPos.z >= 0) {
        putBinData(idx, lastIndex, ranges.z&0xFFFF, (ranges.z>>16)&0xFFFF);
    }

    //TODO: Put unsigned quads at the begining? since it should be cheaper
    putBinData(idx, lastIndex, (ranges.z>>16)&0xFFFF, ranges.w&0xFFFF);




    quadCount = lastIndex;

    //Emit enough mesh shaders such that max(gl_GlobalInvocationID.x)>=quadCount
    gl_TaskCountNV = (lastIndex+MESH_WORKLOAD_PER_INVOCATION-1)/MESH_WORKLOAD_PER_INVOCATION;
}

void main() {
    uint sectionId = gl_WorkGroupID.x;

    if (!shouldRenderVisible(sectionId)) {
        //Early exit if the section isnt visible
        gl_TaskCountNV = 0;
        return;
    }

    ivec4 header = sectionData[sectionId].header;
    ivec3 chunk = ivec3(header.xyz)>>8;
    chunk.y >>= 16;
    chunk -= chunkPosition.xyz;

    origin = vec3(chunk<<4);
    baseOffset = (uint)header.w;

    populateTasks(chunk, (uvec4)sectionData[sectionId].renderRanges);
}