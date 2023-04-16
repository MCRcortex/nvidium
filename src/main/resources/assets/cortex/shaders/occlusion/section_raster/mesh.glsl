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

#import <cortex:occlusion/scene.glsl>

layout(local_size_x = 8) in;
layout(triangles, max_vertices=8, max_primitives=12) out;

taskNV in Task {
    uint32_t _visOutBase;//Base output visibility index
    uint32_t _offset;
    uint8_t _count;
};

const uint PILUTA[] = {0, 3, 6, 0, 1, 7, 4, 5};
const uint PILUTB[] = {1, 2, 6, 4, 0, 7, 6, 4};
const uint PILUTC[] = {2, 0, 4, 5, 1, 3, 7, 2};
const uint PILUTD[] = {1, 2, 0, 5, 5, 1, 7, 7};

const uint PILUTE[] = {6, 2, 3, 7};

void emitIndicies(int visIndex) {
    gl_PrimitiveIndicesNV[(gl_LocalInvocationID.x<<2)|0] = PILUTA[gl_LocalInvocationID.x];
    gl_PrimitiveIndicesNV[(gl_LocalInvocationID.x<<2)|1] = PILUTB[gl_LocalInvocationID.x];
    gl_PrimitiveIndicesNV[(gl_LocalInvocationID.x<<2)|2] = PILUTC[gl_LocalInvocationID.x];
    gl_PrimitiveIndicesNV[(gl_LocalInvocationID.x<<2)|3] = PILUTD[gl_LocalInvocationID.x];
    gl_MeshPrimitivesNV[gl_LocalInvocationID.x].gl_PrimitiveID = visIndex;
}
void emitParital(int visIndex) {
    gl_PrimitiveIndicesNV[(8*4)+gl_LocalInvocationID.x] = PILUTE[gl_LocalInvocationID.x];
    gl_MeshPrimitivesNV[gl_LocalInvocationID.x+8].gl_PrimitiveID = visIndex;
}

void main() {
    uvec4 header = sectionData[_offset|gl_WorkGroupID.x].header;
    uvec3 mins = header.xyz&0xF;
    uvec3 maxs = mins+((header.xyz>>4)&0xF)+1;
    ivec3 chunk = ivec3(header.xyz)>>8;
    chunk.y >>= 16;
    ivec3 relativeChunkPos = (chunk - chunkPosition.xyz);
    vec3 corner = vec3(relativeChunkPos<<4);


    //TODO: try mix instead or something other than just ternaries, i think they get compiled to a cmov type instruction but not sure
    corner += ivec3(((gl_LocalInvocationID.x&1)==0)?mins.x:maxs.x, ((gl_LocalInvocationID.x&4)==0)?mins.y:maxs.y, ((gl_LocalInvocationID.x&2)==0)?mins.z:maxs.z);
    gl_MeshVerticesNV[gl_LocalInvocationID.x].gl_Position = (MVP*vec4(corner, 1.0));
    int visibilityIndex = (int)(_visOutBase|gl_WorkGroupID.x);


    emitIndicies(visibilityIndex);
    if (gl_LocalInvocationID.x < 4) {
        emitParital(visibilityIndex);
    }
    if (gl_LocalInvocationID.x == 0) {//Check for backface block culling
        uint8_t msk = (uint8_t)(1<<UNASSIGNED);
        //TODO: Instead of emitting a mask, could generate the render bounds directly in here since it
        // should already be in cache and fast to do TODO: explore this
        msk |= (uint8_t)((relativeChunkPos.y<=0)?(1<<UP):0);
        msk |= (uint8_t)((relativeChunkPos.y>=0)?(1<<DOWN):0);
        msk |= (uint8_t)((relativeChunkPos.x<=0)?(1<<EAST):0);
        msk |= (uint8_t)((relativeChunkPos.x>=0)?(1<<WEST):0);
        msk |= (uint8_t)((relativeChunkPos.z<=0)?(1<<SOUTH):0);
        msk |= (uint8_t)((relativeChunkPos.z>=0)?(1<<NORTH):0);
        sectionFaceVisibility[visibilityIndex] = msk;

        //Set frameid to old old frame to stop maybe visibility every 256 frames
        sectionVisibility[visibilityIndex] = (uint8_t)(int8_t(frameId)-int8_t(10));


        gl_PrimitiveCountNV = 16;
    }
}