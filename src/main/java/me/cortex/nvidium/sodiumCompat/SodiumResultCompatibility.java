package me.cortex.nvidium.sodiumCompat;

import me.jellysquid.mods.sodium.client.model.quad.properties.ModelQuadFacing;
import me.jellysquid.mods.sodium.client.render.chunk.compile.ChunkBuildResult;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import org.joml.Vector3i;
import org.lwjgl.system.MemoryUtil;

public class SodiumResultCompatibility {
    public static int getTotalGeometryQuadCount(ChunkBuildResult result) {
        return result.meshes.values().stream().mapToInt(a->(a.getVertexData().getLength()/20)/4).sum();
    }

    //Everything is /6*4 cause its in indices and we want verticies
    public static void uploadChunkGeometry(long uploadBuffer, short[] outOffsets, ChunkBuildResult result) {
        int formatSize = 20;
        int offset = 0;

        //Do translucent first
        var translucentData  = result.meshes.get(DefaultTerrainRenderPasses.TRANSLUCENT);
        if (translucentData != null) {
            for (int i = 0; i < 7; i++) {
                var part = translucentData.getPart(ModelQuadFacing.values()[i]);
                if (part != null) {
                    long src = MemoryUtil.memAddress(translucentData.getVertexData().getDirectBuffer()) + (long) part.vertexStart() * formatSize;
                    long dst = uploadBuffer + offset * 4L * formatSize;
                    MemoryUtil.memCopy(src, dst, (long) part.vertexCount() * formatSize);

                    //Update the meta bits of the model format
                    for (int j = 0; j < part.vertexCount(); j++) {
                        short flags = (short) 0b000;//No mipping, No alpha cut
                        MemoryUtil.memPutShort(dst+ (long) j *formatSize+ 6L, flags);//Note: the 6 here is the offset into the vertex format
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
                var part = solid.getPart(ModelQuadFacing.values()[i]);
                if (part != null) {
                    long src = MemoryUtil.memAddress(solid.getVertexData().getDirectBuffer()) + (long) part.vertexStart() * formatSize;
                    long dst = uploadBuffer + offset * 4L * formatSize;
                    MemoryUtil.memCopy(src, dst, (long) part.vertexCount() * formatSize);

                    //Update the meta bits of the model format
                    for (int j = 0; j < part.vertexCount(); j++) {
                        short flags = (short) 0b100;//No mipping, No alpha cut
                        MemoryUtil.memPutShort(dst+ (long) j *formatSize+ 6L, flags);//Note: the 6 here is the offset into the vertex format
                    }

                    offset += part.vertexCount()/4;
                }
            }
            if (cutout != null) {
                var part = cutout.getPart(ModelQuadFacing.values()[i]);
                if (part != null) {
                    long src = MemoryUtil.memAddress(cutout.getVertexData().getDirectBuffer()) + (long) part.vertexStart() * formatSize;
                    long dst = uploadBuffer + offset * 4L * formatSize;
                    MemoryUtil.memCopy(src, dst, (long) part.vertexCount() * formatSize);

                    //Update the meta bits of the model format
                    for (int j = 0; j < part.vertexCount(); j++) {
                        short flags = (short) 0b100;//TODO: FIXME! this needs to be derived from sodiums value as they do something similar
                        MemoryUtil.memPutShort(dst+ (long) j *formatSize+ 6L, flags);//Note: the 6 here is the offset into the vertex format
                    }

                    offset += part.vertexCount()/4;
                }
            }
            outOffsets[i] = (short) (offset - poff);
        }
    }

    //TODO: FIXME: dont use these bounds as they are not accurate (e.g. grass can take up multiple blocks cause vertices extend outside of block)
    public static Vector3i getMinBounds(ChunkBuildResult result) {
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
    public static Vector3i getSizeBounds(ChunkBuildResult result) {
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
