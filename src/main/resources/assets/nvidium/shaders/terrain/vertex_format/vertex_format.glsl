#define COLOR_SCALE        1.0 / 255.0

#ifdef USE_SODIUM_VERTEX_FORMAT
#import <nvidium:terrain/vertex_format/sodium_vertex_format.glsl>
#else
#import <nvidium:terrain/vertex_format/nvidium_vertex_format.glsl>
#endif

vec4 sampleLight(vec2 uv) {
    //Its divided by 16 to match sodium/vanilla (it can never be 1 which is funny)
#ifdef OIT_ALPHA_ONLY
    return vec4(1.0);
#else
    return vec4(texture(tex_light, uv).rgb, 1);
#endif
}

vec3 computeMultiplier(Vertex V) {
    vec4 tint = decodeVertexColour(V);
    tint *= sampleLight(decodeLightUV(V));
    tint *= tint.w;
    return tint.xyz;
}