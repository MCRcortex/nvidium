package me.cortex.nvidium.sodiumCompat;

import it.unimi.dsi.fastutil.longs.LongArrays;
import me.cortex.nvidium.Nvidium;
import me.cortex.nvidium.config.TranslucencySortingLevel;
import net.caffeinemc.mods.sodium.client.model.quad.properties.ModelQuadFacing;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.data.BuiltSectionMeshParts;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkMeshFormats;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.impl.CompactChunkVertex;
import net.caffeinemc.mods.sodium.client.util.NativeBuffer;
import net.minecraft.client.Minecraft;
import org.joml.Vector3i;
import org.lwjgl.system.MemoryUtil;

public class SodiumResultCompatibility {

    public static RepackagedSectionOutput repackage(ChunkBuildOutput result, int formatSize) {
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
        var repackagedGeometry = new RepackagedSectionOutput((geometryBytes/formatSize)/4, output, offsets, min, size);
        //NvidiumGeometryReencoder.transpileGeometry(repackagedGeometry);
        return repackagedGeometry;
    }


    private static void copyQuad(long from, long too) {
        //Quads are 64 bytes big using NvidiumCompactChunkVertex otherwise 80 bytes using CompactChunkVertex
        long quadSize = Nvidium.config.use_sodium_vertex_format ?
                CompactChunkVertex.STRIDE * 4 :
                NvidiumCompactChunkVertex.STRIDE * 4;
        MemoryUtil.memCopy(from, too, quadSize);
    }

    private static int[] computeRanges(int[] segments) {
        var ranges = new int[14];

        int offset = 0;
        for (int i = 0; i < ModelQuadFacing.COUNT; i++) {
            var count = segments[i * 2];
            var facing = segments[i * 2 + 1];
            if (count > 0) {
                ranges[facing * 2 + 1] = count;
                ranges[facing * 2] = offset;
            }
            offset += count;
        }

        return ranges;
    }

    private static int copyRange(BuiltSectionMeshParts meshData, int offset, int count, long dst, int formatSize, Vector3i min, Vector3i max) {
        long src = MemoryUtil.memAddress(meshData.getVertexData().getDirectBuffer()) + (long) offset * formatSize;
        MemoryUtil.memCopy(src, dst, (long) count * formatSize);

        //Update the meta bits of the model format
        for (int j = 0; j < count; j++) {
            long base = dst + (long) j * formatSize;
            updateSectionBounds(min, max, base);
        }

        return count / 4;
    }

    //Everything is /6*4 cause its in indices and we want verticies
    private static void packageSectionGeometry(int formatSize, NativeBuffer output, short[] outOffsets, ChunkBuildOutput result, Vector3i min, Vector3i max) {
        int offset = 0;
        long outPtr = MemoryUtil.memAddress(output.getDirectBuffer());

        //Do translucent first
        var translucentData = result.meshes.get(DefaultTerrainRenderPasses.TRANSLUCENT);

        // If we are using sodium translucency sorting, we don't need to sort quads
        if (translucentData != null && Nvidium.config.translucency_sorting_level == TranslucencySortingLevel.SODIUM) {
            var translucentRanges = computeRanges(translucentData.getVertexSegments());
            for (int i = 0; i < ModelQuadFacing.COUNT; i++) {
                offset += copyRange(
                        translucentData,
                        translucentRanges[i * 2],
                        translucentRanges[i * 2 + 1],
                        outPtr + offset * 4L * formatSize,
                        formatSize,
                        min,
                        max
                );
            }
        } else if (translucentData != null) {
            //NOTE: mutates the input translucent geometry
            var cameraPos = Minecraft.getInstance().gameRenderer.mainCamera().position();

            float cpx = (float) (cameraPos.x - (result.section.getChunkX()<<4));
            float cpy = (float) (cameraPos.y - (result.section.getChunkY()<<4));
            float cpz = (float) (cameraPos.z - (result.section.getChunkZ()<<4));

            {//Project the camera pos onto the bounding outline of the chunk (-8 -> 24 for each axis)
                float len = (float) Math.sqrt(cpx*cpx + cpy*cpy + cpz*cpz);
                cpx *= 1/len;
                cpy *= 1/len;
                cpz *= 1/len;

                //The max range of the camera can be is like 32 blocks away so just use that
                len = Math.min(len, 32);

                cpx *= len;
                cpy *= len;
                cpz *= len;
            }

            int quadCount = translucentData.getVertexData().getLength() / (formatSize * 4);

            int quadId = 0;
            long[] srcs = new long[7];
            long[] sortingData = new long[quadCount];
            var ranges = computeRanges(translucentData.getVertexSegments());

            for (int i = 0; i < ModelQuadFacing.COUNT; i++) {
                var off = ranges[i * 2];
                var count = ranges[i * 2 + 1];

                long src = MemoryUtil.memAddress(translucentData.getVertexData().getDirectBuffer()) + (long) off * formatSize;
                srcs[i] = src;

                float cx = 0;
                float cy = 0;
                float cz = 0;
                //Update the meta bits of the model format
                for (int j = 0; j < count; j++) {
                    long base = src + (long) j * formatSize;

                    float x, y, z;
                    if (Nvidium.config.use_sodium_vertex_format) {
                        int hi = MemoryUtil.memGetInt(base);
                        int lo = MemoryUtil.memGetInt(base + 4);

                        x = scalePos((((hi >>  0) & 0x3FF) << 10) | ((lo >>  0) & 0x3FF));
                        y = scalePos((((hi >> 10) & 0x3FF) << 10) | ((lo >> 10) & 0x3FF));
                        z = scalePos((((hi >> 20) & 0x3FF) << 10) | ((lo >> 20) & 0x3FF));

                    } else {
                        x = decodePosition(MemoryUtil.memGetShort(base));
                        y = decodePosition(MemoryUtil.memGetShort(base + 2));
                        z = decodePosition(MemoryUtil.memGetShort(base + 4));
                    }
                    updateSectionBounds(min, max, base);

                    cx += x;
                    cy += y;
                    cz += z;

                    if ((j&3) == 3) {
                        //Compute the center point of the vertex
                        cx *= 1 / 4f;
                        cy *= 1 / 4f;
                        cz *= 1 / 4f;

                        //Distance to camera
                        float dx = cx-cpx;
                        float dy = cy-cpy;
                        float dz = cz-cpz;

                        float dist = dx*dx + dy*dy + dz*dz;

                        int sortDistance = (int) (dist*(1<<12));

                        //We pack the sorting data
                        long packedSortingData = (((long)sortDistance)<<32)|((((long) j>>2)<<3)|i);
                        sortingData[quadId++] = packedSortingData;

                        cx = 0;
                        cy = 0;
                        cz = 0;
                    }
                }
            }

            if (quadId != sortingData.length) {
                throw new IllegalStateException();
            }

            LongArrays.radixSort(sortingData);

            for (int i = 0; i < sortingData.length; i++) {
                long data = sortingData[i];
                copyQuad(srcs[(int) (data&7)] + ((data>>3)&((1L<<29)-1))*4*formatSize, outPtr + ((sortingData.length-1)-i) * 4L * formatSize);
            }
            offset += quadCount;
        }

        outOffsets[7] = (short) offset;

        var solid  = result.meshes.get(DefaultTerrainRenderPasses.SOLID);
        var cutout = result.meshes.get(DefaultTerrainRenderPasses.CUTOUT);

        //Do all but translucent
        var solidRanges = solid != null ? computeRanges(solid.getVertexSegments()) : null;
        var cutoutRanges = cutout != null ? computeRanges(cutout.getVertexSegments()) : null;

        for (int i = 0; i < ModelQuadFacing.COUNT; i++) {
            int poff = offset;
            if (solid != null) {
                offset += copyRange(
                        solid,
                        solidRanges[i * 2],
                        solidRanges[i * 2 + 1],
                        outPtr + offset * 4L * formatSize,
                        formatSize,
                        min,
                        max
                );
            }
            if (cutout != null) {
                offset += copyRange(
                        cutout,
                        cutoutRanges[i * 2],
                        cutoutRanges[i * 2 + 1],
                        outPtr + offset * 4L * formatSize,
                        formatSize,
                        min,
                        max
                );
            }
            outOffsets[i] = (short) (offset - poff);
        }

        if (offset*4*formatSize != output.getLength()) {
            throw new IllegalStateException("Nvidium bad build result got " + offset*4*formatSize + " instead of " + output.getLength() + " at " +
                    result.section.getChunkX() + " " + result.section.getChunkY() + " " + result.section.getChunkZ());
        }
    }


    private static float decodePosition(short v) {
        return Short.toUnsignedInt(v)*(1f/2048.0f)-8.0f;
    }

    private static float scalePos(int pos) {
        float vertexScale = 32f / (float)((1<<20)-1);
        return (((float)pos) * vertexScale) - 8;
    }

    private static void updateSectionBounds(Vector3i min, Vector3i max, long vertex) {
        float x, y, z;

        if (Nvidium.config.use_sodium_vertex_format) {
            int hi = MemoryUtil.memGetInt(vertex);
            int lo = MemoryUtil.memGetInt(vertex + 4);

            x = scalePos((((hi >>  0) & 0x3FF) << 10) | ((lo >>  0) & 0x3FF));
            y = scalePos((((hi >> 10) & 0x3FF) << 10) | ((lo >> 10) & 0x3FF));
            z = scalePos((((hi >> 20) & 0x3FF) << 10) | ((lo >> 20) & 0x3FF));

        } else {
            x = decodePosition(MemoryUtil.memGetShort(vertex));
            y = decodePosition(MemoryUtil.memGetShort(vertex + 2));
            z = decodePosition(MemoryUtil.memGetShort(vertex + 4));
        }

        updateSectionBounds(min, max, x, y, z);
    }

    private static void updateSectionBounds(Vector3i min, Vector3i max, float x, float y, float z) {
        min.x = (int) Math.min(min.x, Math.floor(x));
        min.y = (int) Math.min(min.y, Math.floor(y));
        min.z = (int) Math.min(min.z, Math.floor(z));

        max.x = (int) Math.max(max.x, Math.ceil(x));
        max.y = (int) Math.max(max.y, Math.ceil(y));
        max.z = (int) Math.max(max.z, Math.ceil(z));
    }
}
