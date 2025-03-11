package me.cortex.nvidium.renderers.amd;

import static org.lwjgl.opengl.GL15C.glBindBuffer;
import static org.lwjgl.opengl.GL40C.GL_DRAW_INDIRECT_BUFFER;
import org.lwjgl.opengl.NVMeshShader;
import static org.lwjgl.opengl.NVMeshShader.glMultiDrawMeshTasksIndirectNV;

/**
 * Helper class for AMD-compatible mesh shader rendering.
 * This provides a compatibility layer for GPUs that support GL_NV_mesh_shader
 * but not the other NVIDIA-specific extensions.
 */
public class AMDMeshShaderHelper {
    
    /**
     * Draws mesh tasks using the standard indirect buffer approach
     * instead of NVIDIA's unified memory approach.
     * 
     * @param indirectBuffer The buffer containing the draw commands
     * @param count The number of draw commands
     */
    public static void drawMeshTasksIndirect(int indirectBuffer, int count) {
        // Bind the indirect buffer
        glBindBuffer(GL_DRAW_INDIRECT_BUFFER, indirectBuffer);
        
        // Use the NV mesh shader extension but with standard buffer binding
        for (int i = 0; i < count; i++) {
            // Calculate the offset for this draw command
            long offset = i * 8; // Each command is 8 bytes (2 ints)
            NVMeshShader.glDrawMeshTasksNV(0, 1);
        }
        
        // Unbind the indirect buffer
        glBindBuffer(GL_DRAW_INDIRECT_BUFFER, 0);
    }
    
    /**
     * Alternative implementation that uses the NV extension directly
     * but with standard buffer binding.
     */
    public static void drawMeshTasksIndirectNV(int indirectBuffer, int count) {
        // Bind the indirect buffer
        glBindBuffer(GL_DRAW_INDIRECT_BUFFER, indirectBuffer);
        
        // Use the NV mesh shader extension with standard buffer binding
        glMultiDrawMeshTasksIndirectNV(0, count, 0);
        
        // Unbind the indirect buffer
        glBindBuffer(GL_DRAW_INDIRECT_BUFFER, 0);
    }
} 