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
uvec4 offsetData;
uint32_t extractOffset(uint idx) {
    if (idx == 0) {
        return 0;
    }
    idx--;
    return (uint16_t)((offsetData[idx>>1]>>((idx&1)*16))&0xFFFF);
}


bool shouldRenderVisible(uint sectionId) {
    return (sectionVisibility[sectionId]&uint8_t(1)) != uint8_t(0);
}

bool shouldRenderSide(uint side, ivec3 relativeChunkPos) {
    if (side == UNASSIGNED) return true;
    uint base = side>>1;
    int dp = relativeChunkPos.yxz[base];
    dp *= ((side&1)==0?1:-1);
    return dp <= 0;
}

void main() {
    uint sectionId = ((gl_WorkGroupID.x)&~(0x7<<29));

    if (!shouldRenderVisible(sectionId)) {
        //Early exit if the section isnt visible
        gl_TaskCountNV = 0;
        return;
    }

    uint side = ((gl_WorkGroupID.x>>29)&7);//Dont need the &

    ivec4 header = sectionData[sectionId].header;

    ivec3 chunk = ivec3(header.xyz)>>8;
    chunk.y >>= 16;
    chunk -= chunkPosition.xyz;
    if (!shouldRenderSide(side, chunk)) {
        gl_TaskCountNV = 0;
        return ;
    }

    originAndBaseData.xyz = vec3(chunk<<4);

    offsetData = (uvec4)sectionData[sectionId].renderRanges;
    uint baseDataOffset = (uint)header.w;
    uint a = extractOffset(side);
    uint b = extractOffset(side+1);
    quadCount = (b-a);
    originAndBaseData.w = uintBitsToFloat(a+baseDataOffset);


    //Emit enough mesh shaders such that max(gl_GlobalInvocationID.x)>=quadCount
    gl_TaskCountNV = (quadCount+MESH_WORKLOAD_PER_INVOCATION-1)/MESH_WORKLOAD_PER_INVOCATION;
}