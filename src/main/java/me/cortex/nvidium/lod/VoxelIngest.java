package me.cortex.nvidium.lod;

import net.caffeinemc.mods.sodium.api.util.ColorABGR;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
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
                    if (state.isAir() || !fillsCube(state)) {
                        continue;
                    }
                    pos.set(baseX + x, baseY + y, baseZ + z);
                    MapColor mapColor = state.getMapColor(level, pos);
                    int argb = mapColor.calculateARGBColor(MapColor.Brightness.NORMAL);
                    int r = (argb >>> 16) & 0xFF;
                    int g = (argb >>> 8) & 0xFF;
                    int b = argb & 0xFF;
                    if (r == 0 && g == 0 && b == 0) {
                        r = 20;
                        g = 20;
                        b = 20;
                    }
                    voxels[i] = (r << 16) | (g << 8) | b | 0x01000000;

                    int block = blockLight == null ? 0 : Math.min(15, blockLight.getLightValue(pos));
                    int sky = skyLight == null ? 15 : Math.min(15, skyLight.getLightValue(pos));
                    light[i] = (byte) (block | (sky << 4));
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
        return state.canOcclude() || state.liquid() || state.isSolid() || state.isSolidRender();
    }
}
