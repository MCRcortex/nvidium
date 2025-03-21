#version 460

#extension GL_ARB_shading_language_include : enable
#pragma optionNV(unroll all)
#define UNROLL_LOOP
#extension GL_NV_mesh_shader : require
#extension GL_NV_gpu_shader5 : require
#extension GL_NV_bindless_texture : require

#extension GL_KHR_shader_subgroup_arithmetic: require
#extension GL_KHR_shader_subgroup_basic : require
#extension GL_KHR_shader_subgroup_ballot : require
#extension GL_KHR_shader_subgroup_vote : require

#import <nvidium:occlusion/scene.glsl>
#import <nvidium:terrain/fog.glsl>
#import <nvidium:terrain/vertex_format.glsl>


//It seems like for terrain at least, the sweat spot is ~16 quads per mesh invocation (even if the local size is not 32 )
layout(local_size_x = 16) in;
layout(triangles, max_vertices=64, max_primitives=32) out;

#ifdef RENDER_FOG
layout(location=1) out Interpolants {
    float fogLerp;
} OUT[];
#endif

taskNV in Task {
    vec3 origin;
    uint baseOffset;
    uint quadCount;
    uint transformationId;

    //Binary search indexs and data
    uvec4 binIa;
    uvec4 binIb;
    uvec4 binVa;
    uvec4 binVb;
};


//Do a binary search via global invocation index to determine the base offset
// Note, all threads in the work group are probably going to take the same path
uint getOffset() {
    uint gii = gl_GlobalInvocationID.x;

    //TODO: replace this with binary search
    if (gii < binIa.x) {
        return binVa.x + gii + baseOffset;
    } else if (gii < binIa.y) {
        return binVa.y + (gii - binIa.x) + baseOffset;
    } else if (gii < binIa.z) {
        return binVa.z + (gii - binIa.y) + baseOffset;
    } else if (gii < binIa.w) {
        return binVa.w + (gii - binIa.z) + baseOffset;
    } else if (gii < binIb.x) {
        return binVb.x + (gii - binIa.w) + baseOffset;
    } else if (gii < binIb.y) {
        return binVb.y + (gii - binIb.x) + baseOffset;
    } else if (gii < binIb.z) {
        return binVb.z + (gii - binIb.y) + baseOffset;
    } else if (gii < binIb.w) {
        return binVb.w + (gii - binIb.z) + baseOffset;
    } else {
        return uint(-1);
    }
}

mat4 transformMat;

vec4 transformVertex(Vertex V) {
    vec3 pos = decodeVertexPosition(V)+origin;
    return MVP*(transformMat * vec4(pos,1.0));
}

Vertex V0;
vec4 pV0;
Vertex V1;
vec4 pV1;
Vertex V2;
vec4 pV2;
Vertex V3;
vec4 pV3;



void putVertex(uint id, Vertex V) {
    #ifdef RENDER_FOG
    vec3 pos = decodeVertexPosition(V)+origin;
    vec3 exactPos = pos+subchunkOffset.xyz;
    OUT[id].fogLerp = clamp(computeFogLerp(exactPos, isCylindricalFog, fogStart, fogEnd) * fogColour.a, 0, 1);
    #endif
}


//TODO: make it so that its 32 threads but still 16 quads, each thread processes 2 verticies
// it computes the min of 0,2 with subgroups, then locally it decieds if its triangle needs to be discarded
// should significantly increase the warp efficency
void main() {
    if (gl_LocalInvocationIndex == 0) {
        gl_PrimitiveCountNV = 0;//Set the prim count to 0
    }

    uint id = getOffset();

    //If its over, dont render
    if (id == uint(-1)) {
        return;
    }
    transformMat = transformationArray[transformationId];

    //Load the data
    V0 = terrainData[(id<<2)+0];
    V1 = terrainData[(id<<2)+1];
    V2 = terrainData[(id<<2)+2];
    V3 = terrainData[(id<<2)+3];

    //Transform the vertices
    pV0 = transformVertex(V0);
    pV1 = transformVertex(V1);
    pV2 = transformVertex(V2);
    pV3 = transformVertex(V3);

    bool t0draw;
    bool t1draw;

    //Compute the bounding pixels of the 2 triangles in the quad. note, vertex 0 and 2 are the common verticies
    {
        vec2 ssmin = ((pV0.xy/pV0.w)+1)*screenSize;
        vec2 ssmax = ssmin;

        vec2 point = ((pV2.xy/pV2.w)+1)*screenSize;
        ssmin = min(ssmin, point);
        ssmax = max(ssmax, point);

        point = ((pV1.xy/pV1.w)+1)*screenSize;
        vec2 t0min = min(ssmin, point);
        vec2 t0max = max(ssmax, point);

        point = ((pV3.xy/pV3.w)+1)*screenSize;
        vec2 t1min = min(ssmin, point);
        vec2 t1max = max(ssmax, point);

        //Possibly cull the triangles if they dont cover the center of a pixel on the screen (degen)
        t0draw = all(notEqual(round(t0min),round(t0max)));
        t1draw = all(notEqual(round(t1min),round(t1max)));
    }

    //Abort if there are no triangles to dispatch
    if (!(t0draw || t1draw)) {
        return;
    }

    //barrier();
    uint triCnt = uint(t0draw)+uint(t1draw);
    //Do a subgroup prefix sum to compute emission indies and verticies, aswell as a max to compute the total count
    uint triIndex = subgroupExclusiveAdd(triCnt);
    uint vertBase = subgroupExclusiveAdd((t0draw==t1draw)?4:3);//if both tris are needed, its 4 verticies else its only 3
    uint totalTris = subgroupMax(triIndex+triCnt);


    uint indexIndex = triIndex*3;//3 indicies to a tri
    uint vertIndex = vertBase;

    //We have triangles to emit!
    // emit the constant vertices (0,2) that are needed for both triangles
    putVertex(vertIndex, V0); gl_MeshVerticesNV[vertIndex++].gl_Position = pV0;
    putVertex(vertIndex, V2); gl_MeshVerticesNV[vertIndex++].gl_Position = pV2;


    uint lodBias = hasMipping(V0)?0:1;
    uint alphaCutoff = rawVertexAlphaCutoff(V0);
    int primData = int((lodBias<<2)|alphaCutoff|(id<<4));

    if (t0draw) {
        putVertex(vertIndex, V1); gl_MeshVerticesNV[vertIndex].gl_Position = pV1;
        // 0 1 2
        gl_PrimitiveIndicesNV[indexIndex++] = vertBase+0;
        gl_PrimitiveIndicesNV[indexIndex++] = vertIndex;
        gl_PrimitiveIndicesNV[indexIndex++] = vertBase+1;
        vertIndex++;

        //gl_MeshPrimitivesNV[triIndex++].gl_PrimitiveID = int(id<<1);
        gl_MeshPrimitivesNV[triIndex++].gl_PrimitiveID = primData|(0<<3);
    }

    if (t1draw) {
        putVertex(vertIndex, V3); gl_MeshVerticesNV[vertIndex].gl_Position = pV3;
        // 2 3 0
        gl_PrimitiveIndicesNV[indexIndex++] = vertBase+1;
        gl_PrimitiveIndicesNV[indexIndex++] = vertIndex;
        gl_PrimitiveIndicesNV[indexIndex++] = vertBase+0;
        vertIndex++;

        //gl_MeshPrimitivesNV[triIndex++].gl_PrimitiveID = int((id<<1)+1);
        gl_MeshPrimitivesNV[triIndex++].gl_PrimitiveID = primData|(1<<3);
    }


    if (subgroupElect()) {
        gl_PrimitiveCountNV = totalTris;
    }
}