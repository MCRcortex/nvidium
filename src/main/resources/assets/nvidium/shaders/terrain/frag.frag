#version 460
#extension GL_ARB_shading_language_include : enable
#pragma optionNV(unroll all)
#define UNROLL_LOOP
#extension GL_NV_gpu_shader5 : require
#extension GL_NV_bindless_texture : require
#extension GL_NV_shader_buffer_load : require

//#extension GL_NV_conservative_raster_underestimation : enable

#extension GL_NV_fragment_shader_barycentric : require


#import <nvidium:occlusion/scene.glsl>
#import <nvidium:terrain/vertex_format.glsl>




layout(location = 0) out vec4 colour;
#if defined(RENDER_FOG) || defined(TRANSLUCENT_PASS)
layout(location = 1) in Interpolants {
    #ifdef RENDER_FOG
    float fogLerp;
    #endif
    #ifdef TRANSLUCENT_PASS
    vec2 uv;
    vec3 v_colour;
    #endif
};
#endif


layout(binding = 1) uniform sampler2D tex_light;

vec4 sampleLight(vec2 uv) {
    //Its divided by 16 to match sodium/vanilla (it can never be 1 which is funny)
    return vec4(texture(tex_light, uv).rgb, 1);
}

vec3 computeMultiplier(Vertex V) {
    vec4 tint = decodeVertexColour(V);
    tint *= sampleLight(decodeLightUV(V));
    tint *= tint.w;
    return tint.xyz;
}


Vertex V0;
Vertex Vp;
Vertex V2;
void computeOutputColour(inout vec3 colour) {
    vec3 multiplier = gl_BaryCoordNV.x*computeMultiplier(V0) + gl_BaryCoordNV.y*computeMultiplier(Vp) + gl_BaryCoordNV.z*computeMultiplier(V2);
    colour *= multiplier;
}

#ifdef RENDER_FOG
//2 ways to do it, either use an interpolation, or screenspace reversal, screenspace reversal is better when many many vertices
// however interpolation increases ISBE
void applyFog(inout vec3 colour) {
    /*
    //Reverse the transformation and compute the original position
    vec4 clip = (MVPInv * vec4((gl_FragCoord.xy/screenSize)-1, gl_FragCoord.z*2-1, 1));
    vec3 pos = clip.xyz/clip.w;
    float fogLerp = clamp(computeFogLerp(pos, isCylindricalFog, fogStart, fogEnd) * fogColour.a, 0,1);
    colour = mix(colour, fogColour.rgb, fogLerp);
    */
    colour = mix(colour, fogColour.rgb, fogLerp);
}
#endif


layout(binding = 0) uniform sampler2D tex_diffuse;
void main() {
    uint quadId = uint(gl_PrimitiveID)>>4;
    bool triangle0 = uint((gl_PrimitiveID>>3)&1)==0;
    uvec3 TRI_INDICIES = triangle0?uvec3(0,1,2):uvec3(2,3,0);
    V0 = terrainData[(quadId<<2)+TRI_INDICIES.x];
    Vp = terrainData[(quadId<<2)+TRI_INDICIES.y];
    V2 = terrainData[(quadId<<2)+TRI_INDICIES.z];


    #ifdef TRANSLUCENT_PASS
    colour = texture(tex_diffuse, uv, 0);
    colour.rgb *= v_colour;
    #else
    vec2 uv = gl_BaryCoordNV.x*decodeVertexUV(V0) + gl_BaryCoordNV.y*decodeVertexUV(Vp) + gl_BaryCoordNV.z*decodeVertexUV(V2);
    colour = texture(tex_diffuse, uv, ((gl_PrimitiveID>>2)&1)*-8.0f);
    if (colour.a < getVertexAlphaCutoff(uint(gl_PrimitiveID&3))) discard;
    colour.a = 1;
    computeOutputColour(colour.rgb);
    #endif

    #ifdef RENDER_FOG
    applyFog(colour.rgb);
    #endif
}