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

//In here add an array that is then "logged" on in the mesh shader to find the draw data
taskNV out Task {
    vec4 originAndBaseData;
    uint quadCount;
};

bool shouldRender(uint sectionId) {
    //Check visibility
    return (sectionVisibility[sectionId]&uint8_t(1)) != uint8_t(0);
}

void main() {
    uint sectionId = gl_WorkGroupID.x;

    if (!shouldRender(sectionId)) {
        //Early exit if the section isnt visible
        gl_TaskCountNV = 0;
        return;
    }

    ivec4 header = sectionData[sectionId].header;
    uint baseDataOffset = (uint)header.w;
    ivec3 chunk = ivec3(header.xyz)>>8;
    chunk.y >>= 16;
    originAndBaseData.xyz = vec3((chunk - chunkPosition.xyz)<<4);

    int offsetData = sectionData[sectionId].renderRanges.w;
    uint a = offsetData&0xFFFF;
    quadCount = ((offsetData>>16)&0xFFFF)-a;
    originAndBaseData.w = uintBitsToFloat(a+baseDataOffset);


    //Emit enough mesh shaders such that max(gl_GlobalInvocationID.x)>=quadCount
    gl_TaskCountNV = (quadCount+MESH_WORKLOAD_PER_INVOCATION-1)/MESH_WORKLOAD_PER_INVOCATION;
}