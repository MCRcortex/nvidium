package me.cortex.nvidium.lod;

import net.caffeinemc.mods.sodium.api.util.ColorABGR;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import net.minecraft.world.level.material.MapColor;

public final class VoxelIngest {
    private VoxelIngest() {}

    public static VoxelSection ingest(ClientLevel level, int sectionX, int sectionY, int sectionZ) {
        LevelChunk chunk = level.getChunk(sectionX, sectionZ);
        int sectionIndex = chunk.getSectionIndexFromSectionY(sectionY);
        if (sectionIndex < 0 || sectionIndex >= chunk.getSectionsCount()) {
            return null;
        }
        LevelChunkSection section = chunk.getSection(sectionIndex);
        if (section.hasOnlyAir()) {
            return new VoxelSection(SectionPos.asLong(sectionX, sectionY, sectionZ), new int[VoxelSection.VOLUME], new byte[VoxelSection.VOLUME]);
        }

        int[] voxels = new int[VoxelSection.VOLUME];
        byte[] light = new byte[VoxelSection.VOLUME];
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int baseX = sectionX << 4;
        int baseY = sectionY << 4;
        int baseZ = sectionZ << 4;

        LayerLightEventListener blockLight = level.getLightEngine().getLayerListener(LightLayer.BLOCK);
        LayerLightEventListener skyLight = level.getLightEngine().getLayerListener(LightLayer.SKY);

        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    BlockState state = section.getBlockState(x, y, z);
                    int i = VoxelSection.index(x, y, z);
                    pos.set(baseX + x, baseY + y, baseZ + z);

                    int block = blockLight == null ? 0 : Math.min(15, blockLight.getLightValue(pos));
                    int sky = skyLight == null ? 15 : Math.min(15, skyLight.getLightValue(pos));
                    for (Direction dir : Direction.values()) {
                        pos.set(baseX + x + dir.getStepX(), baseY + y + dir.getStepY(), baseZ + z + dir.getStepZ());
                        if (blockLight != null) {
                            block = Math.max(block, Math.min(15, blockLight.getLightValue(pos)));
                        }
                        if (skyLight != null) {
                            sky = Math.max(sky, Math.min(15, skyLight.getLightValue(pos)));
                        }
                    }
                    pos.set(baseX + x, baseY + y, baseZ + z);
                    light[i] = (byte) (block | (sky << 4));

                    if (state.isAir() || !fillsCube(state)) {
                        continue;
                    }
                    MapColor mapColor = state.getMapColor(level, pos);
                    if (mapColor == MapColor.NONE) {
                        continue;
                    }
                    int argb = mapColor.calculateARGBColor(MapColor.Brightness.NORMAL);
                    int r = (argb >>> 16) & 0xFF;
                    int g = (argb >>> 8) & 0xFF;
                    int b = argb & 0xFF;
                    if (r + g + b < 24) {
                        continue;
                    }
                    voxels[i] = (r << 16) | (g << 8) | b | 0x01000000;
                }
            }
        }
        return new VoxelSection(SectionPos.asLong(sectionX, sectionY, sectionZ), voxels, light);
    }

    public static int toAbgr(int voxel) {
        int r = (voxel >>> 16) & 0xFF;
        int g = (voxel >>> 8) & 0xFF;
        int b = voxel & 0xFF;
        return ColorABGR.pack(r, g, b, 255);
    }

    private static boolean fillsCube(BlockState state) {
        if (state.liquid() || state.isSolidRender()) {
            return true;
        }
        return state.getBlock() instanceof LeavesBlock;
    }
}
