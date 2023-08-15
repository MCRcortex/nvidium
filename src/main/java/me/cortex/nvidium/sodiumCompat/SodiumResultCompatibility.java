package me.cortex.nvidium.sodiumCompat;

import me.jellysquid.mods.sodium.client.model.quad.properties.ModelQuadFacing;
import me.jellysquid.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import me.jellysquid.mods.sodium.client.util.NativeBuffer;
import org.joml.Vector3f;
import org.joml.Vector3i;
import org.lwjgl.system.MemoryUtil;

public class SodiumResultCompatibility {

    public static RepackagedSectionOutput repackage(ChunkBuildOutput result) {
        int formatSize = 16;
        int geometryBytes = result.meshes.values().stream().mapToInt(a->a.getVertexData().getLength()).sum();
        var output = new NativeBuffer(geometryBytes);
        var offsets = new short[8];
        var min = new Vector3i(2000);
        var max = new Vector3i(-2000);
        packageSectionGeometry(formatSize, output, offsets, result, min, max);

        Vector3i size;
        {
            min.x = Math.max(min.x, 0);
            min.y = Math.max(min.y, 0);
            min.z = Math.max(min.z, 0);
            min.x = Math.min(min.x, 15);
            min.y = Math.min(min.y, 15);
            min.z = Math.min(min.z, 15);

            max.x = Math.min(max.x, 16);
            max.y = Math.min(max.y, 16);
            max.z = Math.min(max.z, 16);
            max.x = Math.max(max.x, 0);
            max.y = Math.max(max.y, 0);
            max.z = Math.max(max.z, 0);

            size =  new Vector3i(max.x - min.x - 1, max.y - min.y - 1, max.z - min.z - 1);

            size.x = Math.min(15, Math.max(size.x, 0));
            size.y = Math.min(15, Math.max(size.y, 0));
            size.z = Math.min(15, Math.max(size.z, 0));
        }

        return new RepackagedSectionOutput((geometryBytes/formatSize)/4, output, offsets, min, size);
    }

    //Everything is /6*4 cause its in indices and we want verticies
    private static void packageSectionGeometry(int formatSize, NativeBuffer output, short[] outOffsets, ChunkBuildOutput result, Vector3i min, Vector3i max) {
        int offset = 0;

        long outPtr = MemoryUtil.memAddress(output.getDirectBuffer());

        //Do translucent first
        var translucentData  = result.meshes.get(DefaultTerrainRenderPasses.TRANSLUCENT);
        if (translucentData != null) {
            for (int i = 0; i < 7; i++) {
                var part = translucentData.getVertexRanges()[i];
                if (part != null) {
                    long src = MemoryUtil.memAddress(translucentData.getVertexData().getDirectBuffer()) + (long) part.vertexStart() * formatSize;
                    long dst = outPtr + offset * 4L * formatSize;
                    MemoryUtil.memCopy(src, dst, (long) part.vertexCount() * formatSize);

                    //Update the meta bits of the model format
                    for (int j = 0; j < part.vertexCount(); j++) {
                        long base = dst+ (long) j * formatSize;
                        short flags = (short) 0b100;//Mipping, No alpha cut
                        MemoryUtil.memPutShort(base + 6L, flags);//Note: the 6 here is the offset into the vertex format

                        updateSectionBounds(min, max, base);
                    }

                    offset += part.vertexCount()/4;
                }
            }
        }
        outOffsets[7] = (short) offset;


        var solid  = result.meshes.get(DefaultTerrainRenderPasses.SOLID);
        var cutout = result.meshes.get(DefaultTerrainRenderPasses.CUTOUT);

        //Do all but translucent
        for (int i = 0; i < 7; i++) {
            int poff = offset;
            if (solid != null) {
                var part = solid.getVertexRanges()[i];
                if (part != null) {
                    long src = MemoryUtil.memAddress(solid.getVertexData().getDirectBuffer()) + (long) part.vertexStart() * formatSize;
                    long dst = outPtr + offset * 4L * formatSize;
                    MemoryUtil.memCopy(src, dst, (long) part.vertexCount() * formatSize);

                    //Update the meta bits of the model format
                    for (int j = 0; j < part.vertexCount(); j++) {
                        long base = dst+ (long) j * formatSize;
                        short flags = (short) 0b100;//Mipping, No alpha cut
                        MemoryUtil.memPutShort(base + 6L, flags);//Note: the 6 here is the offset into the vertex format

                        updateSectionBounds(min, max, base);
                    }

                    offset += part.vertexCount()/4;
                }
            }
            if (cutout != null) {
                var part = cutout.getVertexRanges()[i];
                if (part != null) {
                    long src = MemoryUtil.memAddress(cutout.getVertexData().getDirectBuffer()) + (long) part.vertexStart() * formatSize;
                    long dst = outPtr + offset * 4L * formatSize;
                    MemoryUtil.memCopy(src, dst, (long) part.vertexCount() * formatSize);

                    //Update the meta bits of the model format
                    for (int j = 0; j < part.vertexCount(); j++) {
                        long base = dst+ (long) j * formatSize;
                        short sflags = MemoryUtil.memGetShort(base + 6L);
                        short flags = (short) ((((~sflags)&1)<<2) | ((sflags&(3<<1))>>(1)));
                        MemoryUtil.memPutShort(base + 6L, flags);//Note: the 6 here is the offset into the vertex format

                        updateSectionBounds(min, max, base);
                    }

                    offset += part.vertexCount()/4;
                }
            }
            outOffsets[i] = (short) (offset - poff);
        }
    }


    private static float decodePosition(short v) {
        return Short.toUnsignedInt(v)*(1f/2048.0f)-8.0f;
    }

    private static void updateSectionBounds(Vector3i min, Vector3i max, long vertex) {
        //FIXME: this is a terrible hackfix due to sodium 0.5 not providing chunk bounds anymore
        float x = decodePosition(MemoryUtil.memGetShort(vertex));
        float y = decodePosition(MemoryUtil.memGetShort(vertex + 2));
        float z = decodePosition(MemoryUtil.memGetShort(vertex + 4));

        min.x = (int) Math.min(min.x, Math.floor(x));
        min.y = (int) Math.min(min.y, Math.floor(y));
        min.z = (int) Math.min(min.z, Math.floor(z));

        max.x = (int) Math.max(max.x, Math.ceil(x));
        max.y = (int) Math.max(max.y, Math.ceil(y));
        max.z = (int) Math.max(max.z, Math.ceil(z));
    }


    //TODO: FIXME: dont use these bounds as they are not accurate (e.g. grass can take up multiple blocks cause vertices extend outside of block)
    public Vector3i getMinBounds() {
        int mx = 0;
        int my = 0;
        int mz = 0;
        mx = Math.min(15, mx);
        my = Math.min(15, my);
        mz = Math.min(15, mz);
        return new Vector3i(mx,my,mz);
    }

    //Note: this is adjusted since you cant ever have a size == 0 (the chunk would be air)
    // so its size -1
    public Vector3i getSizeBounds() {
        int sx = 16;
        int sy = 16;
        int sz = 16;
        sx--;
        sy--;
        sz--;
        sx = Math.max(0, sx);
        sy = Math.max(0, sy);
        sz = Math.max(0, sz);
        sx = Math.min(15, sx);
        sy = Math.min(15, sy);
        sz = Math.min(15, sz);
        return new Vector3i(sx,sy,sz);
    }
}
