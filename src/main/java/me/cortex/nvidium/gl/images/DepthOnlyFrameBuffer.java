package me.cortex.nvidium.gl.images;

import com.mojang.blaze3d.platform.GlConst;
import com.mojang.blaze3d.platform.GlStateManager;

import static org.lwjgl.opengl.ARBDirectStateAccess.*;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL11C.glDeleteTextures;
import static org.lwjgl.opengl.GL30C.*;

public class DepthOnlyFrameBuffer {
    public final int width;
    public final int height;
    private final int fid;
    private final int did;
    public DepthOnlyFrameBuffer(int width, int height) {
        this.width = width;
        this.height = height;
        fid = glCreateFramebuffers();
        did = glCreateTextures(GL_TEXTURE_2D);
        glTextureStorage2D(did, 1, GL_DEPTH_COMPONENT32F, width, height);
        glNamedFramebufferTexture(fid, GL_DEPTH_ATTACHMENT, did, 0);
        if (glCheckNamedFramebufferStatus(fid, GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException("ERROR: " + glCheckFramebufferStatus(GL_FRAMEBUFFER));
        }
    }

    public int getDepthBuffer() {
        return did;
    }

    public void bind(boolean setViewport) {
        GlStateManager._glBindFramebuffer(GlConst.GL_FRAMEBUFFER, fid);
        if (setViewport) {
            GlStateManager._viewport(0, 0, width, height);
        }
    }

    public void delete() {
        glDeleteFramebuffers(fid);
        glDeleteTextures(did);
    }
}
