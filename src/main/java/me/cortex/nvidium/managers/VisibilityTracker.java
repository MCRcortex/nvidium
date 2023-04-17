package me.cortex.nvidium.managers;

import me.cortex.nvidium.gl.buffers.Buffer;
import me.cortex.nvidium.util.DownloadTaskStream;
import org.lwjgl.system.MemoryUtil;

//NOTE: the visibility tracker and RenderPipeline must be kept in frame perfect sync else all hell breaks loose
public class VisibilityTracker {
    private final DownloadTaskStream downloadStream;
    private int frameId;
    private final short[][] regions;
    private final byte[] frameIds;
    private final RegionManager regionManager;
    public VisibilityTracker(DownloadTaskStream downloadStream, int frames, RegionManager regionManager) {
        this.downloadStream = downloadStream;
        this.regionManager = regionManager;
        regions = new short[frames][];
        frameIds = new byte[frames];
    }

    public void onFrame(short[] visibleRegions, Buffer regionVisibility) {
        frameIds[(frameId)% regions.length] = (byte) (frameId-1);
        regions[(frameId++)% regions.length] = visibleRegions;
        downloadStream.download(regionVisibility, 0, visibleRegions.length, this::onDownload);
    }

    private void onDownload(long ptr) {
        int pframe = frameId - (this.regions.length - 1);
        var regions = this.regions[pframe%this.regions.length];
        byte frame = frameIds[pframe%this.regions.length];
        for (int i = 0; i < regions.length; i++) {
            if (MemoryUtil.memGetByte(ptr + i) == frame) {
                regionManager.markVisible(regions[i], pframe);
            } else {
                regionManager.markFrustum(regions[i], pframe);
            }
        }
    }

    public void delete() {

    }
}
