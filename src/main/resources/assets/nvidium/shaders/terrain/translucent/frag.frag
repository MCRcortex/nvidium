#version 460
#extension GL_ARB_shading_language_include : enable
#pragma optionNV(unroll all)
#define UNROLL_LOOP
#extension GL_NV_gpu_shader5 : require
#extension GL_NV_bindless_texture : require
#extension GL_NV_shader_buffer_load : require

//#extension GL_NV_conservative_raster_underestimation : enable

//#extension GL_NV_fragment_shader_barycentric : require

#import <nvidium:occlusion/scene.glsl>

layout(location = 0) out vec4 colour;
layout(location = 1) in Interpolants {
    f16vec3 tint;
    f16vec3 addin;
    f16vec2 uv;
};

layout(binding = 0) uniform sampler2D tex_diffuse;

//layout (depth_greater) out float gl_FragDepth;

vec3 rainbow(float level)
{
    /*
        Target colors
        =============

        L  x   color
        0  0.0 vec4(1.0, 0.0, 0.0, 1.0);
        1  0.2 vec4(1.0, 0.5, 0.0, 1.0);
        2  0.4 vec4(1.0, 1.0, 0.0, 1.0);
        3  0.6 vec4(0.0, 0.5, 0.0, 1.0);
        4  0.8 vec4(0.0, 0.0, 1.0, 1.0);
        5  1.0 vec4(0.5, 0.0, 0.5, 1.0);
    */

    float r = float(level <= 2.0) + float(level > 4.0) * 0.5;
    float g = max(1.0 - abs(level - 2.0) * 0.5, 0.0);
    float b = (1.0 - (level - 4.0) * 0.5) * float(level >= 4.0);
    return vec3(r, g, b);
}

vec3 smoothRainbow (float x)
{
    float level1 = floor(x*6.0);
    float level2 = min(6.0, floor(x*6.0) + 1.0);

    vec3 a = rainbow(level1);
    vec3 b = rainbow(level2);

    return mix(a, b, fract(x*6.0));
}

void main() {
    //uint uid = gl_PrimitiveID*132471+123571;
    //colour = vec4(float((uid>>0)&7)/7, float((uid>>3)&7)/7, float((uid>>6)&7)/7, 1.0);
    //colour = vec4(1.0,1.0,0,1);
    colour = texture(tex_diffuse, uv, 0);
    //if (colour.a < 0.05f) discard;
    colour.xyz *= tint;
    colour.xyz += addin;

    /*
    #ifdef TRANSLUCENCY_SORTING
    float special = mod(intBitsToFloat(gl_PrimitiveID)/512f, 1f);
    colour.xyz = smoothRainbow(special);
    colour.a = 1;

    colour.xyz = gl_PrimitiveID * vec3(0.5,0.5,0.5);
    #endif
    */
}