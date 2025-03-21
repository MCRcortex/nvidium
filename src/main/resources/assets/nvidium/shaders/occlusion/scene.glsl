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

ivec3 unpackRegionSize(Region region) {
    return ivec3((region.a>>59)&7, region.a>>62, (region.a>>56)&7);
}

uint unpackRegionTransformId(Region region) {
    return uint((region.b>>(64-24-10))&((1<<10)-1));
}

ivec3 unpackRegionPosition(Region region) {
    //TODO: optimize
    int x = int(int64_t(region.a<<(64-24-24))>>(64-24));
    int y = (int(region.a)<<8)>>8;
    int z = int(int64_t(region.b)>>(64-24));
    return ivec3(x,y,z);
}

int unpackRegionCount(Region region) {
    return int((region.a>>48)&255);
}

bool sectionEmpty(ivec4 header) {
    header.y &= ~0x1FF<<17;
    return header == ivec4(0);
}


layout(std140, binding=0) uniform SceneData {
    //Need to basicly go in order of alignment
    //align(16)
    mat4 MVP;
    #ifdef RENDER_FOG
    mat4 MVPInv;
    #endif
    ivec4 chunkPosition;
    vec4 subchunkOffset;
    vec4 fogColour;

    //vec4  subChunkPosition;//The subChunkTranslation is already done inside the MVP
    //align(8)
    readonly restrict uint16_t *regionIndicies;//Pointer to block of memory at the end of the SceneData struct, also mapped to be a uniform
    readonly restrict Region *regionData;
    restrict Section *sectionData;
    //NOTE: for the following, can make it so that region visibility actually uses section visibility array
    restrict uint8_t *regionVisibility;
    restrict uint8_t *sectionVisibility;
    //Terrain command buffer, the first 4 bytes are actually the count
    writeonly restrict uvec2 *terrainCommandBuffer;
    writeonly restrict uvec2 *translucencyCommandBuffer;

    readonly restrict uint16_t *sortingRegionList;

    //TODO:FIXME: only apply non readonly to translucency mesh
    restrict Vertex *terrainData;//readonly

    //TODO: possibly make this a uniform instead of a buffer, but it might get quite large is the issue
    readonly restrict mat4 *transformationArray;
    readonly restrict uint64_t *originArray;

    //readonly restrict u64vec4 *terrainData;
    //uvec4 *terrainData;

    uint32_t *statistics_buffer;

    vec2 screenSize;

    float fogStart;
    float fogEnd;
    bool isCylindricalFog;

    //align(2)
    uint16_t regionCount;//Number of regions in regionIndicies
    //align(1)
    uint8_t frameId;
};

mat4 getRegionTransformation(Region region) {
    return transformationArray[unpackRegionTransformId(region)];
}

ivec3 unpackOriginOffsetId(uint id) {
    uint64_t val = originArray[id];
    int x = (int(uint(val&0x1ffffff))<<7)>>7;
    int y = (int(uint((val>>50)&0x3fff))<<18)>>18;
    int z = (int(uint((val>>25)&0x1ffffff))<<7)>>7;
    return ivec3(x,y,z);
}