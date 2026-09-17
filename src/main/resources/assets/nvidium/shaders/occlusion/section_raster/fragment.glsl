#version 460
#extension GL_ARB_shading_language_include : enable
#pragma optionNV(unroll all)
#define UNROLL_LOOP
#extension GL_NV_gpu_shader5 : require
#extension GL_NV_bindless_texture : require
#extension GL_NV_shader_buffer_load : require

#import <nvidium:occlusion/scene.glsl>
layout(early_fragment_tests) in;

#ifdef DEBUG
layout(location = 0) out vec4 colour;
void main() {
    uint uid = bitfieldReverse(gl_PrimitiveID*132471+123571);
    colour = vec4(float((uid>>0)&7)/7, float((uid>>3)&7)/7, float((uid>>6)&7)/7, 1.0);
    sectionVisibility[gl_PrimitiveID>>8] = uint8_t(gl_PrimitiveID);
}
#else
void main() {
    sectionVisibility[gl_PrimitiveID>>8] = uint8_t(gl_PrimitiveID);
}
#endif