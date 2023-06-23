package me.cortex.nvidium.sodiumCompat;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.Expose;
import me.cortex.nvidium.Nvidium;
import net.fabricmc.loader.api.FabricLoader;

import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;

public class NvidiumConfig {
    //The options
    public int extra_rd = 100;
    public int fallback_allocation_size = 2048;
    public boolean enable_temporal_coherence = true;
    public int geometry_removing_memory_size = 2048;

    public int region_keep_distance = 32;

    @Expose(serialize = false, deserialize = false)
    public transient boolean disable_graph_update = false;


    private static final Gson GSON = new GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .setPrettyPrinting()
            .excludeFieldsWithModifiers(Modifier.PRIVATE)
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
