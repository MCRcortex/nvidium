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

#define MESH_WORKLOAD_PER_INVOCATION 32

//This is 1 since each task shader workgroup -> multiple meshlets. its not each globalInvocation (afaik)
layout(local_size_x=1) in;

//In here add an array that is then "logged" on in the mesh shader to find the draw data
taskNV out Task {
    vec4 originAndBaseData;
    uint quadCount;
    #ifdef TRANSLUCENCY_SORTING_QUADS
    uint8_t jiggle;
    #endif
};

bool shouldRender(uint sectionId) {
    //Check visibility
    return (sectionVisibility[sectionId]&uint8_t(1)) != uint8_t(0);
}

void main() {
    uint sectionId = gl_WorkGroupID.x;
    #ifdef TRANSLUCENCY_SORTING_SECTIONS
    //Compute indirection for translucency sorting
    {
        ivec4 header = sectionData[sectionId].header;
        //If the section is empty, we dont care about it at all, so ignore it and return
        if (sectionEmpty(header)) {
            return;
        }
        //Compute the redirected section index
        sectionId &= ~0xFF;
        sectionId |= uint((header.y>>18)&0xFF);
    }
    #endif

    if (!shouldRender(sectionId)) {
        //Early exit if the section isnt visible
        //TODO: also early exit if there are no translucents to render
        gl_TaskCountNV = 0;
        return;
    }

    ivec4 header = sectionData[sectionId].header;
    uint baseDataOffset = (uint)header.w;
    ivec3 chunk = ivec3(header.xyz)>>8;
    chunk.y &= 0x1ff;
    chunk.y <<= 32-9;
    chunk.y >>= 32-9;
    originAndBaseData.xyz = vec3((chunk - chunkPosition.xyz)<<4);


    quadCount = ((sectionData[sectionId].renderRanges.w>>16)&0xFFFF);
    #ifdef TRANSLUCENCY_SORTING_QUADS
    jiggle = uint8_t(min(quadCount>>1,(uint(frameId)&1)));//Jiggle by 1 quads (either 0 or 1)//*15
    //jiggle = uint8_t(0);
    quadCount += jiggle;
    originAndBaseData.w = uintBitsToFloat(baseDataOffset - uint(jiggle));
    #else
    originAndBaseData.w = uintBitsToFloat(baseDataOffset);
    #endif

    //Emit enough mesh shaders such that max(gl_GlobalInvocationID.x)>=quadCount
    gl_TaskCountNV = (quadCount+MESH_WORKLOAD_PER_INVOCATION-1)/MESH_WORKLOAD_PER_INVOCATION;

    #ifdef STATISTICS_QUADS
    atomicAdd(statistics_buffer+2, quadCount);
    #endif

    #ifdef STATISTICS_SECTIONS
    atomicAdd(statistics_buffer+1, 1);
    #endif
}