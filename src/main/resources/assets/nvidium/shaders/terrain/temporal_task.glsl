#version 460
//Temporal task shader
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


//This is 1 since each task shader workgroup -> multiple meshlets. its not each globalInvocation (afaik)
layout(local_size_x=1) in;




bool shouldRenderVisible(uint sectionId) {
    uint8_t data = sectionVisibility[sectionId];
    return (data&uint8_t(3)) == uint8_t(1);//If the section was not visible last frame but is visible this frame, render it
}

#import <nvidium:terrain/task_common.glsl>

void main() {
    uint sectionId = gl_WorkGroupID.x;

    if (!shouldRenderVisible(sectionId)) {
        //Early exit if the section isnt visible
        gl_TaskCountNV = 0;
        return;
    }

    ivec4 header = sectionData[sectionId].header;
    ivec3 chunk = ivec3(header.xyz)>>8;
    chunk.y &= 0x1ff;
    chunk.y <<= 32-9;
    chunk.y >>= 32-9;
    chunk -= chunkPosition.xyz;

    origin = vec3(chunk<<4);
    baseOffset = (uint)header.w;

    populateTasks(chunk, uvec4(sectionData[sectionId].renderRanges));

    transformationId = unpackRegionTransformId(regionData[sectionId>>8]);

    #ifdef STATISTICS_QUADS
    atomicAdd(statistics_buffer+2, quadCount);
    #endif
}