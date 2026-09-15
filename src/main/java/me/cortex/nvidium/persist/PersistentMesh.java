package me.cortex.nvidium.persist;

import me.cortex.nvidium.sodiumCompat.RepackagedSectionOutput;
import net.minecraft.core.SectionPos;
import org.joml.Vector3i;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

public final class PersistentMesh {
    public final long sectionKey;
    public final int quads;
    public final int[] offsets;
    public final byte minX, minY, minZ;
    public final byte sizeX, sizeY, sizeZ;
    public final byte[] geometry;
    public final byte lod;

    public PersistentMesh(long sectionKey, int quads, int[] offsets,
                          byte minX, byte minY, byte minZ,
                          byte sizeX, byte sizeY, byte sizeZ,
                          byte[] geometry) {
        this(sectionKey, quads, offsets, minX, minY, minZ, sizeX, sizeY, sizeZ, geometry, (byte) 0);
    }

    public PersistentMesh(long sectionKey, int quads, int[] offsets,
                          byte minX, byte minY, byte minZ,
                          byte sizeX, byte sizeY, byte sizeZ,
                          byte[] geometry, byte lod) {
        this.sectionKey = sectionKey;
        this.quads = quads;
        this.offsets = offsets;
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.geometry = geometry;
        this.lod = lod;
    }

    public static PersistentMesh from(long sectionKey, RepackagedSectionOutput output) {
        byte[] geometry = copyGeometry(output);
        Vector3i min = output.min();
        Vector3i size = output.size();
        return new PersistentMesh(
                sectionKey,
                output.quads(),
                output.offsets().clone(),
                (byte) min.x, (byte) min.y, (byte) min.z,
                (byte) size.x, (byte) size.y, (byte) size.z,
                geometry
        );
    }

    public Vector3i min() {
        return new Vector3i(minX & 0xFF, minY & 0xFF, minZ & 0xFF);
    }

    public Vector3i size() {
        return new Vector3i(sizeX & 0xFF, sizeY & 0xFF, sizeZ & 0xFF);
    }

    public static long regionKey(long sectionKey) {
        return SectionPos.asLong(
                SectionPos.x(sectionKey) >> 3,
                SectionPos.y(sectionKey) >> 2,
                SectionPos.z(sectionKey) >> 3
        );
    }

    private static byte[] copyGeometry(RepackagedSectionOutput output) {
        int length = output.geometry().getLength();
        byte[] geometry = new byte[length];
        ByteBuffer src = output.geometry().getDirectBuffer();
        int pos = src.position();
        int limit = src.limit();
        src.position(0);
        src.limit(length);
        src.get(geometry);
        src.position(pos);
        src.limit(limit);
        return geometry;
    }

    public void copyGeometryTo(long dest) {
        MemoryUtil.memByteBuffer(dest, this.geometry.length).put(this.geometry);
    }
}
