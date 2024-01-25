package me.cortex.nvidium.entities;

public class RenderCache {
    //The idea of this is basicly, you can cache a built render buffer that is attached to a specific layer
    // adding/removing from this cache is extreamly fast (fast enough to be done multiple times a frame)
    // you assign an AABB bounding box and origin (so that the verticies can be transformed across multiple frames)
    // and just dispatch drawing everything in the render cache


    //An idea i have is to make a render hash, which is basicly just a 64 bit hash
    // of all the render data (just using something like xorio)
    // then stuff can get incrementally updated, if something gets rendered and it has a
    // different hash, it will render more frequently, the frequency can also be based
    // on distance to player
    // what this means is e.g. chests which basicly never update/change will only be
    // re rendered very very infrequently same with beds and all other sorts of things
    // however stuff/things like moving entities (zombies cows players etc) will
    // be updated quite frequently

    //Also have a special system for render source providers
    // which are basicly just optimzied ways of generating render geometry on the gpu
    // via virtual instancing or other mekanisms





    //Model format/layout, NOTE: this includes the RenderLayer data as render layer data is small
    //uint16_t (maybe) shaderId (This would also enclose the vertex format)
    //uint16_t textureId (from array of textures, might need to go bindless if a sampler array is not big enough or a texture array for static textures and have 2 bits for mip and blur)
    //bool     culling
    //2 bits,  layering mode
    //3x double world origin
    //3x uint8_t AABB bounding size in blocks
    //1 bit,    index mode (pulls from index data)
    //uint      vertex data offset (if there is index data, it lives before this)
    //uint      Count (quad count if not in index mode)



    //New idea, have quad pulling for non indexed rendering and encode a layer id
    // (or hell encode the layer data directly into the quad, the issue is the dynamic quad sizing)
    //could fix this with a giant index buffer or make all the vertices the same size or just encode the quad id
    //

    //Or actually, use multidraw per different shader/vertex type and store a ptr to a layout data (maybe 11 (or 12) bits leaving 20 bits for drawing the quads (well 18 bits of quads))



}
