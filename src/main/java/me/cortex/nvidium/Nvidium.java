package me.cortex.nvidium;

import org.lwjgl.opengl.GL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Nvidium {
    public static final Logger LOGGER = LoggerFactory.getLogger("Nvidium");
    public static boolean IS_ENABLED = false;
    public static boolean IS_DEBUG = false;
    public static RenderPipeline pipeline;


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
        IS_ENABLED = supported;
        if (IS_ENABLED) {
            LOGGER.info("All capabilities met, enabling nvidium");
        } else {
            LOGGER.info("Not all requirements met, disabling nvidium");
        }
    }
}
