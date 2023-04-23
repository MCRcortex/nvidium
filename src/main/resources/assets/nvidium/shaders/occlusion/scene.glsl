struct Vertex {
    uint16_t a;
    uint16_t b;
    uint16_t c;
    int16_t d;
    int16_t e;
    int16_t f;
    uint16_t g;
    uint16_t h;
    int16_t i;
    int16_t j;
};

// this is cause in the section rasterizer you get less cache misses thus higher throughput
struct Section {
    ivec4 header;
    ivec4 renderRanges;
};

//Header.x -> 0-3=offsetx 4-7=sizex 8-31=chunk x
//Header.y -> 0-3=offsetz 4-7=sizez 8-31=chunk z
//Header.z -> 0-3=offsety 4-7=sizey 8-15=chunk y
//Header.w -> quad offset


#define UP 0
#define DOWN 1
#define EAST 2
#define WEST 3
#define SOUTH 4
#define NORTH 5
#define UNASSIGNED 6



layout(std140, binding=0) uniform SceneData {
    //Need to basicly go in order of alignment
    //align(16)
    mat4 MVP;
    ivec4 chunkPosition;
    //vec4  subChunkPosition;//The subChunkTranslation is already done inside the MVP
    //align(8)
    readonly restrict uint16_t *regionIndicies;//Pointer to block of memory at the end of the SceneData struct, also mapped to be a uniform
    readonly restrict uint64_t *regionData;
    readonly restrict Section *sectionData;
    //NOTE: for the following, can make it so that region visibility actually uses section visibility array
    restrict uint8_t *regionVisibility;
    restrict uint8_t *sectionVisibility;
    restrict uint8_t *sectionFaceVisibility;
    //Terrain command buffer, the first 4 bytes are actually the count
    writeonly restrict uvec2 *terrainCommandBuffer;

    readonly restrict Vertex *terrainData;
    //readonly restrict u64vec4 *terrainData;
    //uvec4 *terrainData;

    //align(2)
    uint16_t regionCount;//Number of regions in regionIndicies
    //align(1)
    uint8_t frameId;
};