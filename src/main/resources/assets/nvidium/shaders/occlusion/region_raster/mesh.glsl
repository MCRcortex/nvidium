#version 460
#extension GL_ARB_shading_language_include : enable
#pragma optionNV(unroll all)
#define UNROLL_LOOP
#extension GL_NV_mesh_shader : require
#extension GL_NV_gpu_shader5 : require
#extension GL_NV_bindless_texture : require
#extension GL_NV_shader_buffer_load : require


#import <nvidium:occlusion/scene.glsl>

#define ADD_SIZE (0.1f/16)

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
    Region data = regionData[regionIndicies[gl_WorkGroupID.x]];//fetch the region data

    int visibilityIndex = (int)gl_WorkGroupID.x;
    //If the region metadata was empty, return
    if (data.a == uint64_t(-1)) {
        regionVisibility[visibilityIndex] = uint8_t(0);
        gl_PrimitiveCountNV = 0;
        return;
    }

    ivec3 pos = unpackRegionPosition(data);
    pos -= chunkPosition.xyz;
    pos -= unpackOriginOffsetId(unpackRegionTransformId(data));
    ivec3 size = unpackRegionSize(data);

    vec3 start = pos - ADD_SIZE;
    vec3 end = start + 1 + size + (ADD_SIZE*2);

    //TODO: Look into only doing 4 locals, for 2 reasons, its more effective for reducing duplicate computation and bandwidth
    // it also means that each thread can emit 3 primatives, 9 indicies each

    //can also do 8 threads then each thread emits a primative and 4 indicies each then the lower 4 emit 1 indice extra each

    vec3 corner = vec3(((gl_LocalInvocationID.x&1)==0)?start.x:end.x, ((gl_LocalInvocationID.x&4)==0)?start.y:end.y, ((gl_LocalInvocationID.x&2)==0)?start.z:end.z);
    corner *= 16.0f;
    gl_MeshVerticesNV[gl_LocalInvocationID.x].gl_Position = MVP*(getRegionTransformation(data)*vec4(corner, 1.0));


    emitIndicies(visibilityIndex);
    if (gl_LocalInvocationID.x < 4) {
        emitParital(visibilityIndex);

        if (gl_LocalInvocationID.x == 0) {
            bool cameraInRegion = all(lessThan(start*16+subchunkOffset.xyz, vec3(ADD_SIZE*16))) && all(lessThan(vec3(-ADD_SIZE*16), end*16+subchunkOffset.xyz));
            regionVisibility[visibilityIndex] = cameraInRegion?uint8_t(1):uint8_t(0);
        }
    }
}