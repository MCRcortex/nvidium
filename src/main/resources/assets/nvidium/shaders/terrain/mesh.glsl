#version 460

#extension GL_ARB_shading_language_include : enable
#pragma optionNV(unroll all)
#define UNROLL_LOOP
#extension GL_NV_mesh_shader : require
#extension GL_NV_gpu_shader5 : require
#extension GL_NV_bindless_texture : require

#extension GL_KHR_shader_subgroup_arithmetic: require
#extension GL_KHR_shader_subgroup_basic : require
#extension GL_KHR_shader_subgroup_vote : require
#extension GL_KHR_shader_subgroup_shuffle : require

layout(binding = 1) uniform sampler2D tex_light;

#import <nvidium:occlusion/scene.glsl>
#import <nvidium:terrain/vertex_format/vertex_format.glsl>

#ifdef RENDER_FOG
#import <sodium:include/fog.glsl>
#endif

//It seems like for terrain at least, the sweat spot is ~16 quads per mesh invocation (even if the local size is not 32 )
layout(local_size_x = 32) in;
layout(triangles, max_vertices=64, max_primitives=32) out;

#ifndef USE_NV_FRAGMENT_SHADER_BARYCENTRIC
layout(location=1) out Interpolants {
#ifdef RENDER_FOG
    vec2 v_FragDistance;
#endif
    vec2 uv;
    vec3 v_colour;
} OUT[];
#endif

taskNV in Task {
    uvec4 binStarts;
    uvec4 binOffsets;

    vec3 origin;
    uint quadCount;
    uint transformationId;
};


//Do a binary search via global invocation index to determine the base offset
// Note, all threads in the work group are probably going to take the same path
uint getOffset() {
    uint gii = gl_GlobalInvocationID.x>>1;
    bvec4 le = lessThan(uvec4(gii), binStarts);
    /*
    //This is so jank and funny
    return dot(binOffsets,notEqual(bvec4(ge.yzw,true), not(ge.xyzw)));
    */

    //TODO:IDEA, since x is always false (i.e. binStarts[0] == 0) we can use that extra space to pack more of the offset bits
    // this allows us to use a single uvec4 to transmit an entire section
    // since max size is 16 bit, we need 2/3 extra bits to store worst case, which we can
    // it does mean we need to readd the baseOffset to the task, but that contains inbuilt start offset of binOffsets.x
    uint retval = binOffsets.w;
    if (le.y) {//x is always true
        retval = binOffsets.x;
    } else if (le.z) {
        retval = binOffsets.y;
    } else if (le.w) {
        retval = binOffsets.z;
    }
    return retval+gii;
}

mat4 transformMat;

vec4 transformVertex(Vertex V) {
    vec3 pos = decodeVertexPosition(V)+origin;
    return MVP*(transformMat * vec4(pos,1.0));
}

Vertex Vc;
vec4 pVc;
Vertex V;
vec4 pV;

void putVertex(uint id, Vertex V) {
#ifndef USE_NV_FRAGMENT_SHADER_BARYCENTRIC
    #ifdef RENDER_FOG
    vec3 pos = decodeVertexPosition(V)+origin;
    vec3 exactPos = pos+subchunkOffset.xyz;
    OUT[id].v_FragDistance = getFragDistance(exactPos);
    #endif

    OUT[id].uv = decodeVertexUV(V);
    OUT[id].v_colour = computeMultiplier(V);
#endif
}

void main() {
    if (gl_LocalInvocationIndex == 0) {
        gl_PrimitiveCountNV = 0;//Set the prim count to 0
    }

    if (quadCount<=(gl_GlobalInvocationID.x>>1)) {
        return;
    }

    uint quadId = getOffset();

    //If its over, dont render
    if (quadId == uint(-1)) {
        return;
    }
    transformMat = transformationArray[transformationId];

    bool triangle1 = (gl_LocalInvocationIndex & uint(1)) == 1;

    //Load corner point, alterenated w.r.t neighbor thread
    Vc = terrainData[(quadId<<2)+(triangle1?2:0)];

    //Load our unique vertex V1 or V3 depending on triangle0
    V = terrainData[(quadId<<2)+(triangle1?3:1)];

    //Transform common and our vertices
    pVc = transformVertex(Vc);
    pV = transformVertex(V);

    bool draw = true;
    bool peerDraw = true;

#ifdef CULL_DEGENERATE_TRIANGLES
    { //Compute the bounding pixels of the current triangle in the quad. note, vertex 0 and 2 are the common verticies
        vec2 ssmin = ((pVc.xy/pVc.w)+1)*screenSize;
        vec2 ssmax = ssmin;

        //We exchange data of side thread common vertex here
        vec2 pVc2 = subgroupShuffleXor(ssmin, 1u);
        ssmin = min(ssmin, pVc2);
        ssmax = max(ssmax, pVc2);

        vec2 point = ((pV.xy/pV.w)+1)*screenSize;
        vec2 tmin = min(ssmin, point);
        vec2 tmax = max(ssmax, point);

        //Possibly cull the triangles if they dont cover the center of a pixel on the screen (degen)
        float degenBias = 0.01f;
        draw = all(notEqual(round(tmin-degenBias),round(tmax+degenBias)));

        // Exchage results with neighbor
        peerDraw = subgroupShuffleXor(draw, 1u);

        // Abort if quad got culled
        if (!(draw || peerDraw)) {
            return;
        }
    }
    #endif

    uint qId = (gl_LocalInvocationIndex&uint(~1))*2;
    //emit the common vertex
    gl_MeshVerticesNV[qId+uint(triangle1)].gl_Position = pVc;
    putVertex(qId+uint(triangle1), Vc);
    if (draw) {
        uint uId = qId+uint(triangle1)+2;
        //emit our vertex
        gl_MeshVerticesNV[uId].gl_Position = pV;
        putVertex(uId, V);

        //Unsure if this is needed
        //subgroupBarrier();
        uint triId = subgroupExclusiveAdd(1);

        //Note indexing is bit funky here since we inserted in inverted order vert 0 is at idx 1 and vert 2 is at 0
        gl_PrimitiveIndicesNV[triId * 3 + 0] = qId+uint(triangle1); // Common vertex 1
        gl_PrimitiveIndicesNV[triId * 3 + 1] = uId; //Emit unique vertex
        gl_PrimitiveIndicesNV[triId * 3 + 2] = qId+uint(!triangle1); // Common vertex 2

        //Emit primitive
        gl_MeshPrimitivesNV[triId].gl_PrimitiveID = int(quadId<<1) | int(triangle1);

        uint triCount = subgroupMax(triId);
        if (subgroupElect()) {
            gl_PrimitiveCountNV = triCount+1;
            #ifdef STATISTICS_CULL
            atomicAdd(statistics_buffer+3, (32-1)-triCount); // Count culled triangles
            #endif
        }
    }

    //Common vertex depending on warp id
    //putVertex(vertBase, triangle0 ? V0 : V2);
}