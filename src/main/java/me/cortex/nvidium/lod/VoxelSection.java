package me.cortex.nvidium.lod;

public final class VoxelSection {
    public static final int SIZE = 16;
    public static final int VOLUME = SIZE * SIZE * SIZE;

    public final long sectionKey;
    public final int[] voxels;
    public final byte[] light;
    private transient VoxelSection[] mipCache;

    public VoxelSection(long sectionKey, int[] voxels, byte[] light) {
        this.sectionKey = sectionKey;
        this.voxels = voxels;
        this.light = light;
    }

    public static int index(int x, int y, int z) {
        return (y << 8) | (z << 4) | x;
    }

    public boolean isEmpty() {
        for (int voxel : this.voxels) {
            if (voxel != 0) {
                return false;
            }
        }
        return true;
    }

    public VoxelSection mip(int lod) {
        if (lod <= 0) {
            return this;
        }
        if (this.mipCache == null) {
            this.mipCache = new VoxelSection[5];
        }
        if (this.mipCache[lod] != null) {
            return this.mipCache[lod];
        }
        int step = 1 << lod;
        int dst = SIZE / step;
        int[] out = new int[VOLUME];
        byte[] outLight = new byte[VOLUME];
        for (int y = 0; y < dst; y++) {
            for (int z = 0; z < dst; z++) {
                for (int x = 0; x < dst; x++) {
                    int di = index(x, y, z);
                    mipInto(out, outLight, di, x * step, y * step, z * step, step);
                }
            }
        }
        VoxelSection mipped = new VoxelSection(this.sectionKey, out, outLight);
        this.mipCache[lod] = mipped;
        return mipped;
    }

    public static boolean occupied(int voxel) {
        return voxel != 0;
    }

    public static int rgb(int voxel) {
        return voxel & 0x00FFFFFF;
    }

    private void mipInto(int[] out, byte[] outLight, int di, int ox, int oy, int oz, int step) {
        int[] colors = new int[8];
        int[] counts = new int[8];
        int unique = 0;
        int bestColor = 0;
        int bestCount = 0;
        int lightAcc = 0;
        int lightN = 0;
        for (int y = 0; y < step; y++) {
            for (int z = 0; z < step; z++) {
                for (int x = 0; x < step; x++) {
                    int i = index(ox + x, oy + y, oz + z);
                    int v = this.voxels[i];
                    lightAcc += Byte.toUnsignedInt(this.light[i]);
                    lightN++;
                    if (v == 0) {
                        continue;
                    }
                    int rgb = v & 0x00FFFFFF;
                    int slot = -1;
                    for (int u = 0; u < unique; u++) {
                        if (colors[u] == rgb) {
                            slot = u;
                            break;
                        }
                    }
                    if (slot == -1 && unique < colors.length) {
                        slot = unique++;
                        colors[slot] = rgb;
                    }
                    if (slot != -1) {
                        counts[slot]++;
                        if (counts[slot] > bestCount) {
                            bestCount = counts[slot];
                            bestColor = rgb;
                        }
                    }
                }
            }
        }
        byte avgLight = (byte) (lightN == 0 ? 0xFF : (lightAcc / lightN));
        if (bestCount == 0) {
            out[di] = 0;
            outLight[di] = avgLight;
            return;
        }
        out[di] = bestColor | 0x01000000;
        outLight[di] = avgLight;
    }
}

