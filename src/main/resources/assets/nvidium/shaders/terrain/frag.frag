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
    f16vec2 uv;
    f16vec3 tint;
    f16vec3 addin;
};

layout(location=5) perprimitiveNV in PerPrimData {
    int8_t lodBias;
    uint8_t alphaCutoff;
} prim_in;


layout(binding = 0) uniform sampler2D tex_diffuse;

//layout (depth_greater) out float gl_FragDepth;

void main() {
    //uint uid = gl_PrimitiveID*132471+123571;
    //colour = vec4(float((uid>>0)&7)/7, float((uid>>3)&7)/7, float((uid>>6)&7)/7, 1.0);
    //colour = vec4(1.0,1.0,0,1);
    colour = texture(tex_diffuse, uv, float(prim_in.lodBias) * (1.0 / 16.0));
    if (colour.a < float(prim_in.alphaCutoff) * (1.0 / 255.0)) discard;
    colour.xyz *= tint;
    colour.xyz += addin;
    //colour = vec4(1.0,(uv_bias.z/-8.1f)+0.001f,0,1);
}