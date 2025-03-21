package me.cortex.nvidium.sodiumCompat;

import it.unimi.dsi.fastutil.ints.IntArrays;
import it.unimi.dsi.fastutil.longs.LongArrays;
import me.cortex.nvidium.Nvidium;
import me.jellysquid.mods.sodium.client.model.quad.properties.ModelQuadFacing;
import me.jellysquid.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import me.jellysquid.mods.sodium.client.util.NativeBuffer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.Vec3d;
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
        var repackagedGeometry = new RepackagedSectionOutput((geometryBytes/formatSize)/4, output, offsets, min, size);
        //NvidiumGeometryReencoder.transpileGeometry(repackagedGeometry);
        return repackagedGeometry;
    }


    private static void copyQuad(long from, long too) {
        //Quads are 64 bytes big
        for (long i = 0; i < 64; i+=8) {
            MemoryUtil.memPutLong(too + i, MemoryUtil.memGetLong(from + i));
        }
    }

    //Everything is /6*4 cause its in indices and we want verticies
    private static void packageSectionGeometry(int formatSize, NativeBuffer output, short[] outOffsets, ChunkBuildOutput result, Vector3i min, Vector3i max) {
        int offset = 0;

        long outPtr = MemoryUtil.memAddress(output.getDirectBuffer());
        //NOTE: mutates the input translucent geometry

        var cameraPos = MinecraftClient.getInstance().gameRenderer.getCamera().getPos();

        float cpx = (float) (cameraPos.x - (result.render.getChunkX()<<4));
        float cpy = (float) (cameraPos.y - (result.render.getChunkY()<<4));
        float cpz = (float) (cameraPos.z - (result.render.getChunkZ()<<4));

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

        //Do translucent first
        var translucentData  = result.meshes.get(DefaultTerrainRenderPasses.TRANSLUCENT);
        if (translucentData != null) {
            int quadCount = 0;
            for (int i = 0; i < 7; i++) {
                var part = translucentData.getVertexRanges()[i];
                quadCount += part != null?part.vertexCount()/4:0;
            }
            int quadId = 0;
            long[] sortingData = new long[quadCount];
            long[] srcs = new long[7];
            for (int i = 0; i < 7; i++) {
                var part = translucentData.getVertexRanges()[i];
                if (part != null) {
                    long src = MemoryUtil.memAddress(translucentData.getVertexData().getDirectBuffer()) + (long) part.vertexStart() * formatSize;
                    srcs[i] = src;

                    float cx = 0;
                    float cy = 0;
                    float cz = 0;
                    //Update the meta bits of the model format
                    for (int j = 0; j < part.vertexCount(); j++) {
                        long base = src + (long) j * formatSize;
                        byte flags = (byte) 0b100;//Mipping, No alpha cut
                        MemoryUtil.memPutByte(base + 6L, flags);//Note: the 6 here is the offset into the vertex format

                        float x = decodePosition(MemoryUtil.memGetShort(base));
                        float y = decodePosition(MemoryUtil.memGetShort(base + 2));
                        float z = decodePosition(MemoryUtil.memGetShort(base + 4));
                        updateSectionBounds(min, max, x, y, z);

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
                        byte flags = (byte) 0b100;//Mipping, No alpha cut
                        MemoryUtil.memPutByte(base + 6L, flags);//Note: the 6 here is the offset into the vertex format

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
                        long base = dst + (long) j * formatSize;
                        short sflags = MemoryUtil.memGetByte(base + 6L);
                        short mipbits = (short) ((sflags&(3<<1))>>1);
                        //mipping, remap 0.5 cut to 0.1 when iris is loaded
                        if (mipbits == 0b10 && IrisCheck.IRIS_LOADED) {
                            mipbits = 0b01;
                        }
                        byte flags = (byte) (((sflags&1)<<2) | mipbits);
                        MemoryUtil.memPutByte(base + 6L, flags);//Note: the 6 here is the offset into the vertex format

                        updateSectionBounds(min, max, base);
                    }

                    offset += part.vertexCount()/4;
                }
            }
            outOffsets[i] = (short) (offset - poff);
        }

        if (offset*4*formatSize != output.getLength()) {
            throw new IllegalStateException();
        }
    }


    private static float decodePosition(short v) {
        return Short.toUnsignedInt(v)*(1f/2048.0f)-8.0f;
    }

    private static void updateSectionBounds(Vector3i min, Vector3i max, long vertex) {
        float x = decodePosition(MemoryUtil.memGetShort(vertex));
        float y = decodePosition(MemoryUtil.memGetShort(vertex + 2));
        float z = decodePosition(MemoryUtil.memGetShort(vertex + 4));
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
