package me.cortex.nvidium.lod;

import me.cortex.nvidium.persist.PersistentMesh;
import me.cortex.nvidium.sodiumCompat.NvidiumCompactChunkVertex;
import net.caffeinemc.mods.sodium.client.model.quad.properties.ModelQuadFacing;
import org.joml.Vector3i;
import org.lwjgl.system.MemoryUtil;

import java.util.ArrayList;
import java.util.List;

public final class LodMeshBuilder {
    private static final int STRIDE = NvidiumCompactChunkVertex.STRIDE;
    private static final int POSITION_MAX_VALUE = 65536;
    private static final float MODEL_ORIGIN = 8.0f;
    private static final float MODEL_RANGE = 32.0f;
    private static final int TEXTURE_MAX_VALUE = 32768;

    private LodMeshBuilder() {}

    public static PersistentMesh build(VoxelSection lod0, int lod) {
        return build(lod0, lod, new VoxelSection[6]);
    }

    public static PersistentMesh build(VoxelSection lod0, int lod, VoxelSection[] faceNeighbors) {
        VoxelSection src = lod <= 0 ? lod0 : lod0.mip(lod);
        int grid = VoxelSection.SIZE >> Math.max(lod, 0);
        if (grid < 1) {
            grid = 1;
        }
        int voxelSize = VoxelSection.SIZE / grid;
        VoxelSection[] neighbors = faceNeighbors != null ? faceNeighbors : new VoxelSection[6];

        List<int[]>[] faces = new List[ModelQuadFacing.COUNT];
        for (int i = 0; i < faces.length; i++) {
            faces[i] = new ArrayList<>();
        }

        meshAxis(src, neighbors, grid, voxelSize, 0, faces);
        meshAxis(src, neighbors, grid, voxelSize, 1, faces);
        meshAxis(src, neighbors, grid, voxelSize, 2, faces);

        int totalQuads = 0;
        int[] offsets = new int[8];
        for (int i = 0; i < 6; i++) {
            offsets[i] = faces[i].size();
            totalQuads += faces[i].size();
        }
        offsets[7] = 0;
        if (totalQuads == 0) {
            return null;
        }

        byte[] geometry = new byte[totalQuads * 4 * STRIDE];
        java.nio.ByteBuffer nativeGeom = MemoryUtil.memAlloc(geometry.length);
        long ptr = MemoryUtil.memAddress(nativeGeom);
        long write = ptr;
        Vector3i min = new Vector3i(16, 16, 16);
        Vector3i max = new Vector3i(0, 0, 0);

        for (int facing = 0; facing < 6; facing++) {
            for (int[] quad : faces[facing]) {
                write = writeQuad(write, quad, min, max);
            }
        }

        nativeGeom.position(0);
        nativeGeom.get(geometry);
        MemoryUtil.memFree(nativeGeom);

        Vector3i size = new Vector3i(15, 15, 15);
        min.x = 0;
        min.y = 0;
        min.z = 0;
        return new PersistentMesh(lod0.sectionKey, totalQuads, offsets,
                (byte) min.x, (byte) min.y, (byte) min.z,
                (byte) size.x, (byte) size.y, (byte) size.z,
                geometry, (byte) Math.max(lod, 0));
    }

    private static void meshAxis(VoxelSection src, VoxelSection[] neighbors, int grid, int voxelSize, int axis, List<int[]>[] faces) {
        int uAxis = (axis + 1) % 3;
        int vAxis = (axis + 2) % 3;
        boolean[][] mask = new boolean[grid][grid];
        int[][] colors = new int[grid][grid];
        byte[][] lights = new byte[grid][grid];

        for (int side = 0; side < 2; side++) {
            int facing = facingOf(axis, side);
            for (int slice = 0; slice < grid; slice++) {
                for (int v = 0; v < grid; v++) {
                    for (int u = 0; u < grid; u++) {
                        int[] p = map(axis, uAxis, vAxis, slice, u, v);
                        int voxel = src.voxels[VoxelSection.index(p[0], p[1], p[2])];
                        boolean solid = VoxelSection.occupied(voxel);
                        boolean exposed = isExposed(src, neighbors, grid, p[0], p[1], p[2], axis, side);
                        mask[u][v] = solid && exposed;
                        colors[u][v] = voxel;
                        lights[u][v] = faceLight(src, grid, p[0], p[1], p[2], axis, side);
                    }
                }
                greedy(mask, colors, lights, grid, slice, side, axis, voxelSize, faces[facing]);
            }
        }
    }

    private static boolean isExposed(VoxelSection src, VoxelSection[] neighbors, int grid, int x, int y, int z, int axis, int side) {
        int nx = x;
        int ny = y;
        int nz = z;
        int delta = side == 0 ? 1 : -1;
        if (axis == 0) {
            nx += delta;
        } else if (axis == 1) {
            ny += delta;
        } else {
            nz += delta;
        }
        if (nx >= 0 && ny >= 0 && nz >= 0 && nx < grid && ny < grid && nz < grid) {
            return !VoxelSection.occupied(src.voxels[VoxelSection.index(nx, ny, nz)]);
        }
        int face = axis * 2 + (side == 0 ? 0 : 1);
        VoxelSection neighbor = neighbors.length > face ? neighbors[face] : null;
        if (neighbor == null) {
            return true;
        }
        int lx = nx;
        int ly = ny;
        int lz = nz;
        if (lx < 0) {
            lx = grid - 1;
        } else if (lx >= grid) {
            lx = 0;
        }
        if (ly < 0) {
            ly = grid - 1;
        } else if (ly >= grid) {
            ly = 0;
        }
        if (lz < 0) {
            lz = grid - 1;
        } else if (lz >= grid) {
            lz = 0;
        }
        return !VoxelSection.occupied(neighbor.voxels[VoxelSection.index(lx, ly, lz)]);
    }

    private static void greedy(boolean[][] mask, int[][] colors, byte[][] lights, int grid,
                               int slice, int side, int axis, int voxelSize, List<int[]> out) {
        for (int v = 0; v < grid; v++) {
            for (int u = 0; u < grid; ) {
                if (!mask[u][v]) {
                    u++;
                    continue;
                }
                int color = colors[u][v];
                byte light = lights[u][v];
                int width = 1;
                while (u + width < grid && mask[u + width][v] && colors[u + width][v] == color && lights[u + width][v] == light) {
                    width++;
                }
                int height = 1;
                outer:
                while (v + height < grid) {
                    for (int du = 0; du < width; du++) {
                        if (!mask[u + du][v + height] || colors[u + du][v + height] != color || lights[u + du][v + height] != light) {
                            break outer;
                        }
                    }
                    height++;
                }
                out.add(new int[]{axis, side, slice, u, v, width, height, color, Byte.toUnsignedInt(light), voxelSize});
                for (int dv = 0; dv < height; dv++) {
                    for (int du = 0; du < width; du++) {
                        mask[u + du][v + dv] = false;
                    }
                }
                u += width;
            }
        }
    }

    private static long writeQuad(long ptr, int[] q, Vector3i min, Vector3i max) {
        int axis = q[0];
        int side = q[1];
        int slice = q[2];
        int u = q[3];
        int v = q[4];
        int w = q[5];
        int h = q[6];
        int color = VoxelIngest.toAbgr(q[7]);
        int packedLight = q[8];
        int vs = q[9];

        float x0 = 0, y0 = 0, z0 = 0, x1 = 0, y1 = 0, z1 = 0;
        float plane = (slice + (side == 0 ? 1 : 0)) * vs;
        if (axis == 0) {
            x0 = x1 = plane;
            y0 = u * vs;
            y1 = (u + w) * vs;
            z0 = v * vs;
            z1 = (v + h) * vs;
        } else if (axis == 1) {
            y0 = y1 = plane;
            z0 = u * vs;
            z1 = (u + w) * vs;
            x0 = v * vs;
            x1 = (v + h) * vs;
        } else {
            z0 = z1 = plane;
            x0 = u * vs;
            x1 = (u + w) * vs;
            y0 = v * vs;
            y1 = (v + h) * vs;
        }

        float[][] corners = corners(axis, side, x0, y0, z0, x1, y1, z1);
        int block = (packedLight & 0xF) * 16;
        int sky = ((packedLight >>> 4) & 0xF) * 16;
        int light = compactLight(block | (sky << 16));
        for (float[] c : corners) {
            ptr = writeVertex(ptr, c[0], c[1], c[2], color, light);
            bound(min, max, c[0], c[1], c[2]);
        }
        return ptr;
    }

    private static float[][] corners(int axis, int side, float x0, float y0, float z0, float x1, float y1, float z1) {
        // CCW when viewed from outside so Nvidium back-face cull keeps the walls.
        if (axis == 0) {
            if (side == 0) {
                return new float[][]{{x0, y0, z0}, {x0, y1, z0}, {x0, y1, z1}, {x0, y0, z1}};
            }
            return new float[][]{{x0, y0, z1}, {x0, y1, z1}, {x0, y1, z0}, {x0, y0, z0}};
        }
        if (axis == 1) {
            if (side == 0) {
                return new float[][]{{x0, y0, z1}, {x1, y0, z1}, {x1, y0, z0}, {x0, y0, z0}};
            }
            return new float[][]{{x0, y0, z0}, {x1, y0, z0}, {x1, y0, z1}, {x0, y0, z1}};
        }
        if (side == 0) {
            return new float[][]{{x1, y0, z0}, {x1, y1, z0}, {x0, y1, z0}, {x0, y0, z0}};
        }
        return new float[][]{{x0, y0, z0}, {x0, y1, z0}, {x1, y1, z0}, {x1, y0, z0}};
    }

    private static int[] map(int axis, int uAxis, int vAxis, int slice, int u, int v) {
        int[] p = new int[3];
        p[axis] = slice;
        p[uAxis] = u;
        p[vAxis] = v;
        return p;
    }

    private static byte faceLight(VoxelSection src, int grid, int x, int y, int z, int axis, int side) {
        int nx = x;
        int ny = y;
        int nz = z;
        int delta = side == 0 ? 1 : -1;
        if (axis == 0) {
            nx += delta;
        } else if (axis == 1) {
            ny += delta;
        } else {
            nz += delta;
        }
        if (nx < 0 || ny < 0 || nz < 0 || nx >= grid || ny >= grid || nz >= grid) {
            return src.light[VoxelSection.index(x, y, z)];
        }
        return src.light[VoxelSection.index(nx, ny, nz)];
    }

    private static int facingOf(int axis, int side) {
        if (axis == 0) {
            return side == 0 ? ModelQuadFacing.POS_X.ordinal() : ModelQuadFacing.NEG_X.ordinal();
        }
        if (axis == 1) {
            return side == 0 ? ModelQuadFacing.POS_Y.ordinal() : ModelQuadFacing.NEG_Y.ordinal();
        }
        return side == 0 ? ModelQuadFacing.POS_Z.ordinal() : ModelQuadFacing.NEG_Z.ordinal();
    }

    private static long writeVertex(long ptr, float x, float y, float z, int abgr, int light) {
        MemoryUtil.memPutInt(ptr, (encodePosition(x) ) | (encodePosition(y) << 16));
        MemoryUtil.memPutInt(ptr + 4, (encodePosition(z)) | (1 << 16) | ((light & 0xFF) << 24));
        MemoryUtil.memPutInt(ptr + 8, (abgr & 0x00FFFFFF) | (((light >> 8) & 0xFF) << 24));
        MemoryUtil.memPutInt(ptr + 12, encodeTexture(LodSprites.u0, LodSprites.v0));
        return ptr + STRIDE;
    }

    private static int encodePosition(float v) {
        int enc = (int) (((MODEL_ORIGIN + v) / MODEL_RANGE) * POSITION_MAX_VALUE);
        return enc & 0xFFFF;
    }

    private static int encodeTexture(float u, float v) {
        return ((Math.round(u * TEXTURE_MAX_VALUE) & 0xFFFF)) |
                ((Math.round(v * TEXTURE_MAX_VALUE) & 0xFFFF) << 16);
    }

    private static int compactLight(int light) {
        int sky = Math.min(248, Math.max(8, (light >>> 16) & 0xFF));
        int block = Math.min(248, Math.max(8, light & 0xFF));
        return block | (sky << 8);
    }

    private static void bound(Vector3i min, Vector3i max, float x, float y, float z) {
        min.x = Math.min(min.x, (int) Math.floor(x));
        min.y = Math.min(min.y, (int) Math.floor(y));
        min.z = Math.min(min.z, (int) Math.floor(z));
        max.x = Math.max(max.x, (int) Math.ceil(x));
        max.y = Math.max(max.y, (int) Math.ceil(y));
        max.z = Math.max(max.z, (int) Math.ceil(z));
    }
}
