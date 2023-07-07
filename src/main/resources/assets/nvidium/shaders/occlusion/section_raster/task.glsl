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

//This is 1 since each task shader workgroup -> multiple meshlets. its not each globalInvocation (afaik)
layout(local_size_x=1) in;

taskNV out Task {
    uint32_t _visOutBase;// The base offset for the visibility output of the shader
    uint32_t _offset;//start offset for regions (can/should probably be a uint16 since this is just the region id << 8)
    uint8_t _count;//incase i do that 1 mesh shader does multiple cubes
    //uint64_t bitcheck[4];//TODO: MAYBE DO THIS, each bit is whether there a section at that index, doing so is faster than pulling metadata to check if a section is valid or not
};

void main() {
    //TODO: see whats faster, atomicAdd (for mdic) or dispatching alot of empty calls (mdi)
    //TODO: experiment with emitting 8 workgroups with the 8th always being 0
    // doing so would enable to batch memory write 2 commands
    // thus taking 4 mem moves instead of 7

    //Emit 7 workloads per chunk
    uint cmdIdx = gl_WorkGroupID.x;

    //Early exit if the region wasnt visible
    if (regionVisibility[gl_WorkGroupID.x] == uint8_t(0)) {
        terrainCommandBuffer[cmdIdx] = uvec2(0);
        return;
    }

    //FIXME: It might actually be more efficent to just upload the region data straight into the ubo
    uint32_t offset = regionIndicies[gl_WorkGroupID.x];
    uint64_t data = regionData[offset];
    uint8_t count = (uint8_t)((data>>48)&0xFF);

    //Write in order
    _visOutBase = offset<<8;//This makes checking visibility very fast and quick in the compute shader
    _offset = offset<<8;
    _count = count;

    gl_TaskCountNV = count;

    terrainCommandBuffer[cmdIdx] = uvec2(uint32_t(count), _visOutBase);
}
