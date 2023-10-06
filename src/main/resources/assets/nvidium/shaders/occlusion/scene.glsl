#define Vertex uvec4

// this is cause in the section rasterizer you get less cache misses thus higher throughput
struct Section {
    ivec4 header;
    //Header.x -> 0-3=offsetx 4-7=sizex 8-31=chunk x
    //Header.y -> 0-3=offsetz 4-7=sizez 8-31=chunk z
    //Header.z -> 0-3=offsety 4-7=sizey 8-15=chunk y
    //Header.w -> quad offset

    ivec4 renderRanges;
};

struct Region {
    uint64_t a;
    uint64_t b;
};




layout(std140, binding=0) uniform SceneData {
    //Need to basicly go in order of alignment
    //align(16)
    mat4 MVP;
    ivec4 chunkPosition;
    vec4 subchunkOffset;
    vec4 fogColour;

    //vec4  subChunkPosition;//The subChunkTranslation is already done inside the MVP
    //align(8)
    readonly restrict uint16_t *regionIndicies;//Pointer to block of memory at the end of the SceneData struct, also mapped to be a uniform
    readonly restrict Region *regionData;
    readonly restrict Section *sectionData;
    //NOTE: for the following, can make it so that region visibility actually uses section visibility array
    restrict uint8_t *regionVisibility;
    restrict uint8_t *sectionVisibility;
    //Terrain command buffer, the first 4 bytes are actually the count
    writeonly restrict uvec2 *terrainCommandBuffer;

    readonly restrict Vertex *terrainData;
    //readonly restrict u64vec4 *terrainData;
    //uvec4 *terrainData;

    uint32_t *statistics_buffer;

    float fogStart;
    float fogEnd;
    bool isCylindricalFog;

    //align(2)
    uint16_t regionCount;//Number of regions in regionIndicies
    //align(1)
    uint8_t frameId;
};