#version 460 core

//64 bytes
struct Model {
    ivec3 blockOffset;
    uint boxSize;

    ivec2 innerBlockOffset_count;//16 bit sub block precision
    uint shader_culling_layering_indexing;
    uint textureA_textureB;
};