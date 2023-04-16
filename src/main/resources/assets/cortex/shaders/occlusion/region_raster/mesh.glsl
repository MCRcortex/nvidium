#version 460
#extension GL_ARB_shading_language_include : enable
#pragma optionNV(unroll all)
#define UNROLL_LOOP
#extension GL_NV_mesh_shader : require
#extension GL_NV_gpu_shader5 : require
#extension GL_NV_bindless_texture : require
#extension GL_NV_shader_buffer_load : require


#import <cortex:occlusion/scene.glsl>

//TODO: maybe do multiple cubes per workgroup? this would increase utilization of individual sm's
layout(local_size_x = 8) in;
layout(triangles, max_vertices=8, max_primitives=12) out;

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
    gl_PrimitiveCountNV = 12;
}

void main() {
    //FIXME: It might actually be more efficent to just upload the region data straight into the ubo
    // this remove an entire level of indirection and also puts region data in the very fast path
    uint64_t data = regionData[regionIndicies[gl_WorkGroupID.x]];//fetch the region data

    //TODO: maybe use shuffle operations to compute the shape

    uint32_t count = (uint32_t)((data>>48)&0xFF);
    int32_t startY = (int32_t)((int8_t)(data>>40));             //In chunk coordinates
    int32_t startZ = (((int32_t)(data>>8))>>12);    //In chunk coordinates
    int32_t startX = (((int32_t)(data<<12))>>12);   //In chunk coordinates
    int32_t endY = startY + 1 + (int32_t)((data>>62)&0x3);//(technically dont need the 0x3)
    int32_t endX = startX + 1 + (int32_t)((data>>59)&0x7);
    int32_t endZ = startZ + 1 + (int32_t)((data>>56)&0x7);
    //TODO: Look into only doing 4 locals, for 2 reasons, its more effective for reducing duplicate computation and bandwidth
    // it also means that each thread can emit 3 primatives, 9 indicies each

    //can also do 8 threads then each thread emits a primative and 4 indicies each then the lower 4 emit 1 indice extra each

    vec3 corner = vec3((ivec3(((gl_LocalInvocationID.x&1)==0)?startX:endX, ((gl_LocalInvocationID.x&4)==0)?startY:endY, ((gl_LocalInvocationID.x&2)==0)?startZ:endZ) - chunkPosition.xyz)<<4);
    gl_MeshVerticesNV[gl_LocalInvocationID.x].gl_Position = MVP*vec4(corner, 1.0);

    int visibilityIndex = (int)gl_WorkGroupID.x;
    //Set visibility to old to prevent wrap around flickering
    regionVisibility[visibilityIndex] = (uint8_t)(int8_t(frameId)-int8_t(10));

    emitIndicies(visibilityIndex);
    if (gl_LocalInvocationID.x < 4) {
        emitParital(visibilityIndex);
    }
}