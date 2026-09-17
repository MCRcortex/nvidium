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

layout(local_size_x = 32) in;
layout(triangles, max_vertices=32, max_primitives=48) out;

const uint PILUTA[] = {0, 3, 6, 0, 1, 7, 4, 5};
const uint PILUTB[] = {1, 2, 6, 4, 0, 7, 6, 4};
const uint PILUTC[] = {2, 0, 4, 5, 1, 3, 7, 2};
const uint PILUTD[] = {1, 2, 0, 5, 5, 1, 7, 7};

const uint PILUTE[] = {6, 2, 3, 7};
void emitIndicies(uint batchIdx, int visIndex) {
    uint tid = gl_LocalInvocationID.x % 8;
    uint baseIdx = batchIdx * 36;
    uint baseVtx = batchIdx * 8;
    gl_PrimitiveIndicesNV[baseIdx + ((tid<<2)|0)] = PILUTA[tid] + baseVtx;
    gl_PrimitiveIndicesNV[baseIdx + ((tid<<2)|1)] = PILUTB[tid] + baseVtx;
    gl_PrimitiveIndicesNV[baseIdx + ((tid<<2)|2)] = PILUTC[tid] + baseVtx;
    gl_PrimitiveIndicesNV[baseIdx + ((tid<<2)|3)] = PILUTD[tid] + baseVtx;
    gl_MeshPrimitivesNV[batchIdx * 12 + tid].gl_PrimitiveID = visIndex;
}

void emitParital(uint batchIdx, int visIndex) {
    uint tid = gl_LocalInvocationID.x % 8;
    uint baseIdx = batchIdx * 36;
    uint baseVtx = batchIdx * 8;
    gl_PrimitiveIndicesNV[baseIdx + 32 + tid] = PILUTE[tid] + baseVtx;
    gl_MeshPrimitivesNV[batchIdx * 12 + 8 + tid].gl_PrimitiveID = visIndex;
}

void main() {
    uint baseIdx = gl_WorkGroupID.x * 4;
    if (baseIdx >= uint(regionCount)) {
        if (gl_LocalInvocationID.x == 0) gl_PrimitiveCountNV = 0;
        return;
    }

    uint batchIdx = gl_LocalInvocationID.x / 8;
    uint tid = gl_LocalInvocationID.x % 8;
    uint visibilityIndex = baseIdx + batchIdx;

    if (visibilityIndex >= uint(regionCount)) {
        if (tid == 0) regionVisibility[visibilityIndex] = uint8_t(0);
        gl_MeshVerticesNV[gl_LocalInvocationID.x].gl_Position = vec4(0.0);
        emitIndicies(batchIdx, 0);
        if (tid < 4) emitParital(batchIdx, 0);
        if (gl_LocalInvocationID.x == 0) gl_PrimitiveCountNV = 48;
        return;
    }

    //FIXME: It might actually be more efficent to just upload the region data straight into the ubo
    // this remove an entire level of indirection and also puts region data in the very fast path
    Region data = regionData[regionIndicies[visibilityIndex]];//fetch the region data

    //If the region metadata was empty, return
    if (data.a == uint64_t(-1)) {
        if (tid == 0) regionVisibility[visibilityIndex] = uint8_t(0);
        gl_MeshVerticesNV[gl_LocalInvocationID.x].gl_Position = vec4(0.0);
        emitIndicies(batchIdx, int(visibilityIndex));
        if (tid < 4) emitParital(batchIdx, int(visibilityIndex));
        if (gl_LocalInvocationID.x == 0) gl_PrimitiveCountNV = 48;
        return;
    }

    ivec3 pos = unpackRegionPosition(data);
    pos -= chunkPosition.xyz;
    pos -= unpackOriginOffsetId(unpackRegionTransformId(data));
    ivec3 size = unpackRegionSize(data);

    vec3 start = pos - ADD_SIZE;
    vec3 end = start + 1 + size + (ADD_SIZE*2);

    vec3 corner = vec3(((tid&1)==0)?start.x:end.x, ((tid&4)==0)?start.y:end.y, ((tid&2)==0)?start.z:end.z);
    corner *= 16.0f;
    gl_MeshVerticesNV[gl_LocalInvocationID.x].gl_Position = MVP*(getRegionTransformation(data)*vec4(corner, 1.0));


    emitIndicies(batchIdx, int(visibilityIndex));
    if (tid < 4) {
        emitParital(batchIdx, int(visibilityIndex));

        if (tid == 0) {
            bool cameraInRegion = all(lessThan(start*16+subchunkOffset.xyz, vec3(ADD_SIZE*16))) && all(lessThan(vec3(-ADD_SIZE*16), end*16+subchunkOffset.xyz));
            regionVisibility[visibilityIndex] = cameraInRegion?uint8_t(1):uint8_t(0);
        }
    }
    if (gl_LocalInvocationID.x == 0) {
        gl_PrimitiveCountNV = 48;
    }
}