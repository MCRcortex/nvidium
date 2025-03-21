package me.cortex.nvidium.managers;

import me.cortex.nvidium.gl.RenderDevice;
import me.cortex.nvidium.gl.buffers.Buffer;
import me.cortex.nvidium.gl.shader.Shader;
import me.cortex.nvidium.sodiumCompat.ShaderLoader;
import me.cortex.nvidium.util.DownloadTaskStream;
import net.minecraft.util.Identifier;
import org.lwjgl.system.MemoryUtil;

import static me.cortex.nvidium.gl.shader.ShaderType.FRAGMENT;
import static me.cortex.nvidium.gl.shader.ShaderType.MESH;
import static org.lwjgl.opengl.GL42.GL_COMMAND_BARRIER_BIT;
import static org.lwjgl.opengl.GL42.glMemoryBarrier;
import static org.lwjgl.opengl.GL43C.GL_SHADER_STORAGE_BARRIER_BIT;
import static org.lwjgl.opengl.NVMeshShader.glDrawMeshTasksNV;

public class RegionVisibilityTracker {
    private final Shader shader = Shader.make()
            .addSource(MESH, ShaderLoader.parse(Identifier.of("nvidium", "occlusion/queries/region/mesh.glsl")))
            .addSource(FRAGMENT, ShaderLoader.parse(Identifier.of("nvidium", "occlusion/queries/region/fragment.frag")))
            .compile();

    private final DownloadTaskStream downStream;
    private final int[] frustum;
    private final int[] visible;
    public RegionVisibilityTracker(DownloadTaskStream downStream, int maxRegions) {
        this.downStream = downStream;
        visible = new int[maxRegions];
        frustum = new int[maxRegions];
        for (int i = 0; i < maxRegions; i++) {
            frustum[i] = 0;
            visible[i] = 0;
        }
    }

    private int fram = 0;
    //This is kind of evil in the fact that it just reuses the visibility buffer
    public void computeVisibility(int regionCount, Buffer regionVisibilityBuffer, short[] regionMapping) {
        shader.bind();
        fram++;
        glDrawMeshTasksNV(0,regionCount);
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
        downStream.download(regionVisibilityBuffer, 0, regionCount, ptr -> {
            for (int i = 0; i < regionMapping.length; i++) {
                if (MemoryUtil.memGetByte(ptr + i) == 1) {
                    //System.out.println(regionMapping[i] + " was visible");
                    frustum[regionMapping[i]]++;
                    visible[regionMapping[i]] = fram;
                } else {
                    //System.out.println(regionMapping[i] + " was not visible");
                    frustum[regionMapping[i]]++;
                }
            }
        });
    }


    public void delete() {
        shader.delete();
    }

    public void resetRegion(int id) {
        frustum[id] = 0;
        visible[id] = 0;
    }

    public int findMostLikelyLeastSeenRegion(int maxIndex) {
        int maxRank = Integer.MIN_VALUE;
        int id = -1;
        for (int i = 0; i < maxIndex; i++) {
            if (frustum[i] <= 200) continue;
            int rank =  - visible[i];
            //int rank = -visible[i];
            if (maxRank < rank) {
                maxRank = rank;
                id = i;
            }
        }
        return id;
    }
}
