package me.cortex.nvidium;

import org.lwjgl.opengl.GL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import me.cortex.nvidium.config.NvidiumConfig;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

public class Nvidium {
    public static final String MOD_VERSION;
    public static final Logger LOGGER = LoggerFactory.getLogger("Amdium");
    public static boolean IS_COMPATIBLE = false;
    public static boolean IS_ENABLED = false;
    public static boolean IS_DEBUG = System.getProperty("nvidium.isDebug", "false").equals("TRUE");
    // AMD GPUs don't support persistent sparse addressable buffers like NVIDIA does
    public static boolean SUPPORTS_PERSISTENT_SPARSE_ADDRESSABLE_BUFFER = false;
    public static boolean FORCE_DISABLE = false;

    public static NvidiumConfig config = NvidiumConfig.loadOrCreate();

    static {
        ModContainer mod = (ModContainer) FabricLoader.getInstance().getModContainer("nvidium").orElseThrow(NullPointerException::new);
        var version = mod.getMetadata().getVersion().getFriendlyString();
        var commit = mod.getMetadata().getCustomValue("commit").getAsString();
        MOD_VERSION = version+"-"+commit;
    }

    public static void checkSystemIsCapable() {
        var cap = GL.getCapabilities();
        // Only require GL_NV_mesh_shader support
        boolean supported = cap.GL_NV_mesh_shader;
        IS_COMPATIBLE = supported;
        if (IS_COMPATIBLE) {
            LOGGER.info("Mesh shader capability met");
        } else {
            LOGGER.warn("Mesh shader requirement not met, disabling Amdium");
        }
        
        // AMD GPUs don't support sparse buffers like NVIDIA does
        SUPPORTS_PERSISTENT_SPARSE_ADDRESSABLE_BUFFER = false;
        LOGGER.info("Using fallback terrain buffer for compatibility, expect increased VRAM usage");

        if (IS_COMPATIBLE) {
            LOGGER.info("Enabling Amdium");
        }
        IS_ENABLED = IS_COMPATIBLE;
    }
}
