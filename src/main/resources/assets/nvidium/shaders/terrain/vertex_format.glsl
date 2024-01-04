#define MODEL_SCALE        32.0 / 65536.0
#define MODEL_ORIGIN       8.0

#define COLOR_SCALE        1.0 / 255.0

vec3 decodeVertexPosition(Vertex v) {
    uvec3 packed_position = uvec3(
        (v.x >>  0) & 0xFFFFu,
        (v.x >> 16) & 0xFFFFu,
        (v.y >>  0) & 0xFFFFu
    );

    return (vec3(packed_position) * MODEL_SCALE) - MODEL_ORIGIN;
}

vec4 decodeVertexColour(Vertex v) {
    uvec3 packed_color = (uvec3(v.z) >> uvec3(0, 8, 16)) & uvec3(0xFFu);
    return vec4(vec3(packed_color) * COLOR_SCALE, 1);
}

vec2 decodeVertexUV(Vertex v) {
    return vec2(v.w&0xffff,v.w>>16)*(1f/(TEXTURE_MAX_SCALE));
}

float decodeVertexMippingBias(Vertex v) {
    return ((v.y>>16)&4)==0?-8:0;
}

float decodeVertexAlphaCutoff(Vertex v) {
    return (float[](0.0f, 0.1f,0.5f))[((v.y>>16)&int16_t(3))];
}

uvec2 decodeLightUV(Vertex v) {
    return (uvec2(v.z) >> uvec2(24, 28)) & uvec2(0xFu);
}

