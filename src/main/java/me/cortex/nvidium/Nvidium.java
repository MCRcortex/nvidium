package me.cortex.nvidium;

import me.cortex.nvidium.sodiumCompat.NvidiumConfig;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.irisshaders.iris.api.v0.IrisApi;
import net.minecraft.util.Util;
import org.lwjgl.opengl.GL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


//NOTE: with sodium async bfs, just reimplement the bfs dont try to convert sodiums bfs into async
public class Nvidium {
    public static final String MOD_VERSION;
    public static final Logger LOGGER = LoggerFactory.getLogger("Nvidium");
    public static boolean IS_COMPATIBLE = false;
    public static boolean IS_ENABLED = false;
    public static boolean IS_DEBUG = System.getProperty("nvidium.isDebug", "false").equals("TRUE");
    public static boolean SUPPORTS_PERSISTENT_SPARSE_ADDRESSABLE_BUFFER = true;

    public static NvidiumConfig config = NvidiumConfig.loadOrCreate();

    static {
        ModContainer mod = (ModContainer) FabricLoader.getInstance().getModContainer("nvidium").orElseThrow(NullPointerException::new);
        MOD_VERSION = mod.getMetadata().getVersion().getFriendlyString();
    }
    //TODO: basicly have the terrain be a virtual geometry buffer
    // once it gets too full, start culling via a callback task system
    // which executes a task on the gpu and calls back once its done
    // use this to then do a rasterizing check on the terrain and remove
    // the oldest regions and sections

    //TODO: ADD LODS

    public static void checkSystemIsCapable() {
        var cap = GL.getCapabilities();
        boolean supported = cap.GL_NV_mesh_shader &&
                cap.GL_NV_uniform_buffer_unified_memory &&
                cap.GL_NV_vertex_buffer_unified_memory &&
                cap.GL_NV_representative_fragment_test &&
                cap.GL_ARB_sparse_buffer &&
                cap.GL_NV_bindless_multi_draw_indirect;
        IS_COMPATIBLE = supported;
        if (IS_COMPATIBLE) {
            LOGGER.info("All capabilities met");
        } else {
            LOGGER.warn("Not all requirements met, disabling nvidium");
        }
        if (IS_COMPATIBLE && Util.getOperatingSystem() == Util.OperatingSystem.LINUX) {
            LOGGER.warn("Linux currently uses fallback terrain buffer due to driver inconsistencies, expect increase vram usage");
            SUPPORTS_PERSISTENT_SPARSE_ADDRESSABLE_BUFFER = false;
        }

        if (IS_COMPATIBLE) {
            LOGGER.info("Enabling Nvidium");
        }
        IS_ENABLED = IS_COMPATIBLE;
    }


    public static void setupGLDebugCallback() {

    }
}
