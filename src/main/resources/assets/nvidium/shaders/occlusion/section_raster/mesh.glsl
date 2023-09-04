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
    int visibilityIndex = (int)(_visOutBase|gl_WorkGroupID.x);

    uint8_t lastData = sectionVisibility[visibilityIndex];
    // this is almost 100% guarenteed not needed afaik
    //barrier();

    uvec4 header = sectionData[_offset|gl_WorkGroupID.x].header;
    vec3 mins = (header.xyz&0xF)-ADD_SIZE;
    vec3 maxs = mins+((header.xyz>>4)&0xF)+1+(ADD_SIZE*2);
    ivec3 chunk = ivec3(header.xyz)>>8;
    chunk.y >>= 16;
    ivec3 relativeChunkPos = (chunk - chunkPosition.xyz);
    vec3 corner = vec3(relativeChunkPos<<4);

    //TODO: try mix instead or something other than just ternaries, i think they get compiled to a cmov type instruction but not sure
    corner += vec3(((gl_LocalInvocationID.x&1)==0)?mins.x:maxs.x, ((gl_LocalInvocationID.x&4)==0)?mins.y:maxs.y, ((gl_LocalInvocationID.x&2)==0)?mins.z:maxs.z);
    gl_MeshVerticesNV[gl_LocalInvocationID.x].gl_Position = (MVP*vec4(corner, 1.0));

    int prim_payload = (visibilityIndex<<8)|int(((uint(lastData))<<1)&0xff)|1;

    emitIndicies(prim_payload);
    if (gl_LocalInvocationID.x < 4) {
        emitParital(prim_payload);
    }
    if (gl_LocalInvocationID.x == 0) {
        ivec3 absRelPos = abs(relativeChunkPos);
        int maxDist = min(absRelPos.x, min(absRelPos.y, absRelPos.z));

        //Shift and set, this gives us a bonus of having the last 8 frames as visibility history
        //sectionVisibility[visibilityIndex] = uint8_t(lastData<<1) | uint8_t(maxDist<=1?1:0);//Inject visibility aswell
        sectionVisibility[visibilityIndex] = uint8_t(lastData<<1) | uint8_t(0);

        gl_PrimitiveCountNV = 12;
    }
}