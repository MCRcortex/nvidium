package me.cortex.nvidium.managers;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import me.cortex.nvidium.gl.RenderDevice;
import me.cortex.nvidium.gl.buffers.IDeviceMappedBuffer;
import me.cortex.nvidium.sodiumCompat.IRepackagedResult;
import me.cortex.nvidium.util.BufferArena;
import me.cortex.nvidium.util.UploadingBufferStream;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSection;
import me.jellysquid.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.minecraft.util.math.ChunkSectionPos;
import org.joml.Vector3i;
import org.joml.Vector4i;
import org.lwjgl.system.MemoryUtil;

public class SectionManager {
    public static final int SECTION_SIZE = 32;

    //Sections should be grouped and batched into sizes of the count of sections in a region
    private final RegionManager regionManager;

    //TODO: maybe replace with a int[] using bit masking thing
    private final Long2IntOpenHashMap sectionOffset = new Long2IntOpenHashMap();

    private final Long2IntOpenHashMap terrainDataLocation = new Long2IntOpenHashMap();

    public final UploadingBufferStream uploadStream;

    private final IDeviceMappedBuffer sectionBuffer;
    public final BufferArena terrainAreana;

    private final RenderDevice device;

    private final int formatSize;

    public SectionManager(RenderDevice device, long fallback_memory_size, UploadingBufferStream uploadStream, int rd, int height, int quadVertexSize) {
        this.device = device;
        this.uploadStream = uploadStream;
        //int widthSquared = (rd*2+1)*(rd*2+1);

        //int maxRegions = (int) Math.ceil((((double) widthSquared*height)/256))*2;
        //TODO: optimize this to be more tight
        int maxRegions = (int)(Math.ceil(Math.pow(Math.ceil((double)rd/8+1),2)*Math.PI) * Math.ceil(((double)height)/4+2));

        this.formatSize = quadVertexSize;
        this.sectionBuffer = device.createDeviceOnlyMappedBuffer((long) maxRegions * (8*4*8) * SECTION_SIZE);
        this.terrainAreana = new BufferArena(device, fallback_memory_size, quadVertexSize);
        this.sectionOffset.defaultReturnValue(-1);
        this.regionManager = new RegionManager(device, maxRegions);
    }

    private long getSectionKey(int x, int y, int z) {
        return ChunkSectionPos.asLong(x,y,z);
    }

    public void uploadChunkBuildResult(ChunkBuildOutput result) {
        var output = ((IRepackagedResult)result).getOutput();
        if (output == null || output.quads() == 0) {
            deleteSection(result.render);
            return;
        }

        RenderSection section = result.render;
        long key = getSectionKey(section.getChunkX(), section.getChunkY(), section.getChunkZ());
        int sectionIdx = sectionOffset.computeIfAbsent(//Get or fetch the section meta index
                key,
                a->regionManager.createSectionIndex(uploadStream, section.getChunkX(), section.getChunkY(), section.getChunkZ())
        );

        if (terrainDataLocation.containsKey(key)) {
            terrainAreana.free(terrainDataLocation.get(key));
        }

        int addr = terrainAreana.allocQuads(output.quads());
        terrainDataLocation.put(key, addr);
        long geoUpload = terrainAreana.upload(uploadStream, addr);

        MemoryUtil.memCopy(MemoryUtil.memAddress(output.geometry().getDirectBuffer()), geoUpload, output.geometry().getLength());

        long segment = uploadStream.getUpload(sectionBuffer, (long) sectionIdx * SECTION_SIZE, SECTION_SIZE);
        Vector3i min  = output.min();
        Vector3i size = output.size();

        //NOTE:TODO: The y encoded height position only has a range of like 6 bits max, that gives 18 bits free/spare for something
        // realistically it would only be 16 free bits cause ee but still thats 2 bytes free

        int px = section.getChunkX()<<8  | size.x<<4 | min.x;
        int py = section.getChunkY()<<24 | size.y<<4 | min.y;//TODO: figure out how to make this << 8 just like the others
        int pz = section.getChunkZ()<<8  | size.z<<4 | min.z;
        int pw = addr;
        new Vector4i(px, py, pz, pw).getToAddress(segment);
        segment += 4*4;

        //Write the geometry offsets, packed into ints
        for (int i = 0; i < 4; i++) {
            int geo = Short.toUnsignedInt(output.offsets()[i*2])|(Short.toUnsignedInt(output.offsets()[i*2+1])<<16);
            MemoryUtil.memPutInt(segment, geo);
            segment += 4;
        }
    }

    public void deleteSection(RenderSection section) {
        deleteSection(getSectionKey(section.getChunkX(), section.getChunkY(), section.getChunkZ()));
    }

    void deleteSection(long sectionKey) {
        int sectionIdx = sectionOffset.remove(sectionKey);
        if (sectionIdx != -1) {
            terrainAreana.free(terrainDataLocation.remove(sectionKey));
            regionManager.removeSectionIndex(uploadStream, sectionIdx);
            //Clear the segment
            long segment = uploadStream.getUpload(sectionBuffer, (long) sectionIdx * SECTION_SIZE, SECTION_SIZE);
            MemoryUtil.memSet(segment, 0, SECTION_SIZE);
        }
    }

    public void commitChanges() {
        uploadStream.commit();
    }

    public void delete() {
        sectionBuffer.delete();
        terrainAreana.delete();
        regionManager.delete();
    }

    public RegionManager getRegionManager() {
        return regionManager;
    }

    public long getSectionDataAddress() {
        return sectionBuffer.getDeviceAddress();
    }

    public int getSectionRegionIndex(int x, int y, int z) {
        return sectionOffset.getOrDefault(getSectionKey(x,y,z), -1);
    }

    public void removeRegionById(int regionId) {
        if (!regionManager.regionExists(regionId)) return;
        long rk = regionManager.regionIdToKey(regionId);
        int X = ChunkSectionPos.unpackX(rk)<<3;
        int Y = ChunkSectionPos.unpackY(rk)<<2;
        int Z = ChunkSectionPos.unpackZ(rk)<<3;
        for (int x = X; x < X+8; x++) {
            for (int y = Y; y < Y+4; y++) {
                for (int z = Z; z < Z+8; z++) {
                    deleteSection(getSectionKey(x, y, z));
                }
            }
        }
    }
}




