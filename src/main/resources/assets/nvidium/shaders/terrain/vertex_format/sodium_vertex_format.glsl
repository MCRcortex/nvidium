const uint POSITION_BITS        = 20u;
const uint POSITION_MAX_COORD   = 1u << POSITION_BITS;
const uint POSITION_MAX_VALUE   = POSITION_MAX_COORD - 1u;

const uint TEXTURE_BITS         = 15u;
const uint TEXTURE_MAX_COORD    = 1u << TEXTURE_BITS;
const uint TEXTURE_MAX_VALUE    = TEXTURE_MAX_COORD - 1u;

const float VERTEX_SCALE = 32.0 / float(POSITION_MAX_COORD);
const float VERTEX_OFFSET = -8.0;

uvec3 _deinterleave_u20x3(Vertex v) {
    uvec3 hi = (uvec3(v.hi) >> uvec3(0u, 10u, 20u)) & 0x3FFu;
    uvec3 lo = (uvec3(v.lo) >> uvec3(0u, 10u, 20u)) & 0x3FFu;

    return (hi << 10u) | lo;
}

vec3 decodeVertexPosition(Vertex v) {
    return (_deinterleave_u20x3(v) * VERTEX_SCALE) + VERTEX_OFFSET;
}

vec2 decodeVertexRawUV(Vertex v) {
    return vec2(v.u & TEXTURE_MAX_VALUE, v.v & TEXTURE_MAX_VALUE) / float(TEXTURE_MAX_COORD);
}

vec2 decodeVertexUVBias(Vertex v) {
    return mix(vec2(-1.0), vec2(1.0), bvec2(uvec2(v.u, v.v) >> TEXTURE_BITS));
}

vec2 decodeVertexUV(Vertex v) {
    return (decodeVertexUVBias(v) * texCoordShrink) + decodeVertexRawUV(v);
}

vec2 decodeLightUV(Vertex v) {
    return vec2(v.blockLight, v.skyLight)/256.0;
}

bool hasMipping(Vertex v) {
    return bool(int(v.material) & 1);
}

vec4 decodeVertexColour(Vertex v) {
    uvec3 packed_color = (uvec3(v.color) >> uvec3(0, 8, 16)) & uvec3(0xFFu);
    return vec4(vec3(packed_color) * COLOR_SCALE, 1);
}