package me.cortex.nvidium.config;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import me.cortex.nvidium.Nvidium;
import net.fabricmc.loader.api.FabricLoader;

import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;

public class NvidiumConfig {
    //The options
    public boolean enable_temporal_coherence = true;
    public int max_geometry_memory = 2048;
    public boolean automatic_memory = true;

    // 32 = ~vanilla RD ring. 257 = keep explored GPU mesh until VRAM eviction.
    public static final int VANILLA_KEEP_DISTANCE = 32;
    public static final int KEEP_ALL_DISTANCE = 257;

    public int region_keep_distance = KEEP_ALL_DISTANCE;

    public boolean keepUntilVramLimit() {
        return this.region_keep_distance >= KEEP_ALL_DISTANCE;
    }

    public int gpuKeepChunks() {
        return this.region_keep_distance;
    }

    public Boolean enable_disk_persistence = Boolean.TRUE;

    public boolean diskPersistence() {
        return this.enable_disk_persistence == null || this.enable_disk_persistence;
    }

    public Boolean enable_lod = Boolean.TRUE;
    public Integer lod_start_chunks = 32;

    public boolean lodEnabled() {
        return this.enable_lod == null || this.enable_lod;
    }

    public int lodStartChunks() {
        return this.lod_start_chunks == null ? 32 : Math.max(8, this.lod_start_chunks);
    }

    public boolean render_fog = true;
    public boolean use_sodium_vertex_format = false;
    public boolean cull_degenerate_triangles = true;
    public boolean use_nv_fragment_shader_barycentric = true;

    public TranslucencySortingLevel translucency_sorting_level = TranslucencySortingLevel.SODIUM;

    public StatisticsLoggingLevel statistics_level = StatisticsLoggingLevel.NONE;


    private static final Gson GSON = new GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .setPrettyPrinting()
            .excludeFieldsWithModifiers(Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
            .create();

    private NvidiumConfig() {}
    public static NvidiumConfig loadOrCreate() {
        var path = getConfigPath();
        if (Files.exists(path)) {
            try (FileReader reader = new FileReader(path.toFile())) {
                return GSON.fromJson(reader, NvidiumConfig.class);
            } catch (IOException e) {
                Nvidium.LOGGER.error("Could not parse config", e);
            }
        }
        return new NvidiumConfig();
    }

    public void save() {
        //Unsafe, todo: fixme! needs to be atomic!
        try {
            Files.writeString(getConfigPath(), GSON.toJson(this));
        } catch (IOException e) {
            Nvidium.LOGGER.error("Failed to write config file", e);
        }
    }

    private static Path getConfigPath() {
        return FabricLoader.getInstance()
                .getConfigDir()
                .resolve("nvidium-config.json");
    }
}
