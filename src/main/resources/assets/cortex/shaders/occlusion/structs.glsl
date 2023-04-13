struct iAABB {
    ivec3 origin;
    ivec3 size;
};

struct fAABB {
    vec3 origin;
    vec3 size;
};


/*
struct RegionV1 {//32 bytes
    ivec4 id_y_z_x;//Count can be a uint16 or even possibly a uint8
    ivec4 start_count_sizexz_sizey_meta;//Size can be a half float
};

//Region size is 8x4x8 chunks
struct RegionV2 {//8 bytes
    //1 byte for shape size in chunks
    //1 byte for count
    //6 bytes for start chunk (each axis is a int16)
};

//Region size is 8x4x8 chunks
//If region is 16 bytes, can store alot more data for culling etc (can also still set it via glClearBuffer)
struct RegionV3 {//8 bytes
    //1 byte for shape size in chunks
    //1 byte for count
    //2.5 bytes for start chunk x,z axis and 1 byte for height
};*/

struct Region {//NOTE: if we up to 16 bytes we can include alot more information
    uint64_t data;
};

/*
//V1
struct Section {//64 bytes
//--header-- // Note: can reorganize and extend to not have 0.5 weird bits etc
//2.5 bytes for start chunk x,z axis and 1 byte for height
//3 bytes for AABB (4 bits per axis, 3 offset, 3 size)
//3 bytes for base terrain offset (in vertex count/4 (quads)) // should probably extend to full 32 bit int
//4 bytes free
    ivec4 header;

//--payload--
//7*3*2+2 bytes for geometry data, which is just consecutive offsets, to get the count subtract offset by next offset
    ivec4[3] packedLayers;
//Have 4 bytes free in the packed layers for something
};*/

//V2
struct Section {//48 bytes
    //--header--
    //3 bytes for AABB (4 bits per axis, 3 offset, 3 size)
    //3 bytes for start chunk x,z axis and 1 byte for height
    //4 bytes for base terrain offset (in vertex count/4 (quads))
    //2 bytes other meta
    // -- total: 16 bytes
    ivec4 header;

    //--payload--
    uint[8] payload;
};



//V3
//16 byte header
// each geometry range is represented by a uint16_t which is the offset in quad count
//payload size: 7*2 == 14
// TOTAL size of a section is 32 bytes



struct UnpackedSectionHeader {
    ivec3 sectionBlockPos;
    ivec3 aabbStartBlock;
    ivec3 aabbEndBlock;
    int geometryStart;
};

UnpackedSectionHeader unpack(const Section section) {
    UnpackedSectionHeader result;

    return result;
}






/*
struct Block { //4 bytes
    //uint28 id;
    //uint4, light
    uint data;
};

struct Section {
    Block[16*16*16] blocks;
};

//8x4x8
struct Region {
    uint[256] sectionIdxs;
};

//Dependent on render distance, uploaded per frame, is the region ids relative to the camera
//Size is number of possible regions in the render distance
struct Regions {
    uint16_t[256] regions;
};


*/





