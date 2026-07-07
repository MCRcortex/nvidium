#version 460
#extension GL_ARB_shading_language_include : enable
#pragma optionNV(unroll all)
#define UNROLL_LOOP
#extension GL_NV_gpu_shader5 : require
#extension GL_NV_bindless_texture : require
#extension GL_NV_shader_buffer_load : require

//#extension GL_NV_conservative_raster_underestimation : enable

#ifdef USE_NV_FRAGMENT_SHADER_BARYCENTRIC
#extension GL_NV_fragment_shader_barycentric : require
#endif

layout(binding = 0) uniform sampler2D tex_diffuse;
layout(binding = 1) uniform sampler2D tex_light;

#moj_import <nvidium:occlusion/scene.glsl>
#moj_import <nvidium:terrain/vertex_format/vertex_format.glsl>

#ifdef RENDER_FOG
#define USE_FOG
#moj_import <sodium:include/fog.glsl>
#endif


layout(location = 0) out vec4 colour;
#ifndef USE_NV_FRAGMENT_SHADER_BARYCENTRIC
layout(location = 1) in Interpolants {
    #ifdef RENDER_FOG
    vec2 v_FragDistance;
    #endif

    vec2 uv;
    vec3 v_colour;
};
#endif

Vertex V0;
Vertex Vp;
Vertex V2;
#ifdef USE_NV_FRAGMENT_SHADER_BARYCENTRIC
void computeOutputColour(inout vec3 colour) {
    vec3 multiplier = gl_BaryCoordNV.x*computeMultiplier(V0) + gl_BaryCoordNV.y*computeMultiplier(Vp) + gl_BaryCoordNV.z*computeMultiplier(V2);
    colour *= multiplier;
}
#endif

#ifdef RENDER_FOG
//2 ways to do it, either use an interpolation, or screenspace reversal, screenspace reversal is better when many many vertices
// however interpolation increases ISBE
void applyFog(inout vec4 colour) {

#ifdef USE_NV_FRAGMENT_SHADER_BARYCENTRIC
    //Reverse the transformation and compute the original position
    vec4 clip = (MVPInv * vec4((gl_FragCoord.xy/screenSize)-1, gl_FragCoord.z, 1));
    vec3 pos = clip.xyz/clip.w;
    vec2 v_FragDistance = getFragDistance(pos);
#endif
    colour = _linearFog(colour, v_FragDistance, fogColour, environmentFog, renderFog, 1.0);
}
#endif

vec4 sampleNearest(vec2 uv, vec2 du, vec2 dv, vec2 texelScreenSize) {
    // Convert our UV back up to texel coordinates and find out how far over we are from the center of each pixel
    vec2 uvTexelCoords = uv / texelSize;
    vec2 texelCenter = round(uvTexelCoords) - 0.5f;
    vec2 texelOffset = uvTexelCoords - texelCenter;

    // Move our offset closer to the texel center based on texel size on screen
    texelOffset = (texelOffset - 0.5f) * texelSize / texelScreenSize + 0.5f;
    texelOffset = clamp(texelOffset, 0.0f, 1.0f);

    vec2 uvCorrected = (texelCenter + texelOffset) * texelSize;
    return textureGrad(tex_diffuse, uvCorrected, du, dv);
}

// Rotated Grid Super-Sampling
vec4 sampleRGSS(vec2 uv, vec2 du, vec2 dv, vec2 texelScreenSize) {
    float maxTexelSize = max(texelScreenSize.x, texelScreenSize.y);

    float minPixelSize = min(texelSize.x, texelSize.y);

    float transitionStart = minPixelSize * 1.0;
    float transitionEnd = minPixelSize * 2.0;
    float blendFactor = smoothstep(transitionStart, transitionEnd, maxTexelSize);

    float duLength = length(du);
    float dvLength = length(dv);
    float minDerivative = min(duLength, dvLength);
    float maxDerivative = max(duLength, dvLength);

    float effectiveDerivative = sqrt(minDerivative * maxDerivative);

    float mipLevelExact = max(0.0, log2(effectiveDerivative / minPixelSize));

    const vec2 offsets[4] = vec2[](
        vec2(0.125, 0.375),
        vec2(-0.125, -0.375),
        vec2(0.375, -0.125),
        vec2(-0.375, 0.125)
    );

    vec4 rgssColor = vec4(0.0);
    for (int i = 0; i < 4; ++i) {
        vec2 sampleUV = uv + offsets[i] * texelSize;
        rgssColor += textureLod(tex_diffuse, sampleUV, mipLevelExact);
    }
    rgssColor *= 0.25;

    vec4 nearestColor = sampleNearest(uv, du, dv, texelScreenSize);

    return mix(nearestColor, rgssColor, blendFactor);
}

void main() {
    uint quadId = uint(gl_PrimitiveID)>>1;
    bool triangle0 = uint((gl_PrimitiveID)&1)==0;
    uvec3 TRI_INDICIES = triangle0?uvec3(0,1,2):uvec3(2,3,0);
    V0 = terrainData[(quadId<<2)+TRI_INDICIES.x];
    Vp = terrainData[(quadId<<2)+TRI_INDICIES.y];
    V2 = terrainData[(quadId<<2)+TRI_INDICIES.z];

    #ifdef USE_NV_FRAGMENT_SHADER_BARYCENTRIC
        float HALF_SHIFT = (1.0f/TEXTURE_MAX_SCALE)/2.0f;
        vec2 uv0 = decodeVertexUV(V0);
        vec2 uvp = decodeVertexUV(Vp);
        vec2 uv2 = decodeVertexUV(V2);
        vec2 uvr = gl_BaryCoordNV.x*uv0 + gl_BaryCoordNV.y*uvp + gl_BaryCoordNV.z*uv2;
        vec2 uv = clamp(uvr, min(min(uv0, uv2),uvp)+HALF_SHIFT, max(max(uv0, uv2),uvp)-HALF_SHIFT);

        vec2 du = dFdx(uvr);
        vec2 dv = dFdy(uvr);
    #else
        vec2 du = dFdx(uv);
        vec2 dv = dFdy(uv);
    #endif
        vec2 texelScreenSize = sqrt(du * du + dv * dv);
        colour = useRGSS() ? sampleRGSS(uv, du, dv, texelScreenSize) : sampleNearest(uv, du, dv, texelScreenSize);

    uint alphaCutoff = rawVertexAlphaCutoff(V0);
    if (colour.a < getVertexAlphaCutoff(alphaCutoff)) {
        discard;
    }

    #ifdef USE_NV_FRAGMENT_SHADER_BARYCENTRIC
        computeOutputColour(colour.rgb);
    #else
        colour.rgb *= v_colour;
    #endif

    #ifdef RENDER_FOG
        applyFog(colour);
    #endif
}