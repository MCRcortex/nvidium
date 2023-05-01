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
        short offset = 0;
        var solid  = result.meshes.get(DefaultTerrainRenderPasses.SOLID);
        var cutout = result.meshes.get(DefaultTerrainRenderPasses.CUTOUT);
        //Do all but translucent
        for (int i = 0; i < 7; i++) {
            if (solid != null) {
                //TODO Optimize from .values()
                var segment = solid.getParts().get(ModelQuadFacing.values()[i]);
                if (segment != null) {
                    MemoryUtil.memCopy(MemoryUtil.memAddress(solid.getVertexData().getDirectBuffer()) + (long) segment.vertexStart() * formatSize,
                            uploadBuffer + offset * 4L * formatSize,
                            (long) segment.vertexCount() * formatSize);
                    offset += segment.vertexCount() / 4;
                }
            }
            if (cutout != null) {
                var segment = cutout.getParts().get(ModelQuadFacing.values()[i]);
                if (segment != null) {
                    MemoryUtil.memCopy(MemoryUtil.memAddress(cutout.getVertexData().getDirectBuffer()) + (long) segment.vertexStart() * formatSize,
                            uploadBuffer + offset * 4L * formatSize,
                            (long) segment.vertexCount() * formatSize);
                    offset += segment.vertexCount() / 4;
                }
            }
            outOffsets[i] = offset;
        }
        //Do translucent
        short translucent = offset;
        var translucentData  = result.meshes.get(DefaultTerrainRenderPasses.TRANSLUCENT);
        if (translucentData != null) {
            for (int i = 0; i < 7; i++) {
                var segment = translucentData.getParts().get(ModelQuadFacing.values()[i]);
                if (segment != null) {
                    MemoryUtil.memCopy(MemoryUtil.memAddress(translucentData.getVertexData().getDirectBuffer()) + (long) segment.vertexStart() * formatSize,
                            uploadBuffer + translucent * 4L * formatSize,
                            (long) segment.vertexCount() * formatSize);
                    translucent += segment.vertexCount() / 4;
                }
            }
        }
        outOffsets[7] = translucent;
    }

    //TODO: FIXME: dont use these bounds as they are not accurate (e.g. grass can take up multiple blocks cause vertices extend outside of block)
    public static Vector3i getMinBounds(ChunkBuildResult result) {
        int mx = (int) (result.data.getBounds().minX - result.render.getOriginX());
        int my = (int) (result.data.getBounds().minY - result.render.getOriginY());
        int mz = (int) (result.data.getBounds().minZ - result.render.getOriginZ());
        mx = Math.min(15, mx);
        my = Math.min(15, my);
        mz = Math.min(15, mz);
        return new Vector3i(mx,my,mz);
    }

    public static Vector3i getSizeBounds(ChunkBuildResult result) {
        int sx = (int)Math.ceil(result.data.getBounds().maxX-result.data.getBounds().minX-1);
        int sy = (int)Math.ceil(result.data.getBounds().maxY-result.data.getBounds().minY-1);
        int sz = (int)Math.ceil(result.data.getBounds().maxZ-result.data.getBounds().minZ-1);
        sx = Math.max(0, sx);
        sy = Math.max(0, sy);
        sz = Math.max(0, sz);
        sx = Math.min(15, sx);
        sy = Math.min(15, sy);
        sz = Math.min(15, sz);
        return new Vector3i(sx,sy,sz);
    }
}
