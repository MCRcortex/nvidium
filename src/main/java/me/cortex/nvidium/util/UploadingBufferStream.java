package me.cortex.nvidium.util;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import me.cortex.nvidium.gl.RenderDevice;
import me.cortex.nvidium.gl.buffers.Buffer;
import me.cortex.nvidium.gl.buffers.PersistentClientMappedBuffer;

import java.lang.ref.WeakReference;
import java.util.LinkedList;
import java.util.List;

import static org.lwjgl.opengl.GL42C.GL_BUFFER_UPDATE_BARRIER_BIT;
import static org.lwjgl.opengl.GL44.GL_CLIENT_MAPPED_BUFFER_BARRIER_BIT;

//TODO: need to add self resizing
public class UploadingBufferStream {
    private final SegmentedManager segments = new SegmentedManager();

    private final RenderDevice device;
    private PersistentClientMappedBuffer buffer;//TODO: make it self resizing if full

    private final List<Batch> batchedCopies = new ReferenceArrayList<>();
    private final LongList batchedFlushes = new LongArrayList();
    private record Batch(Buffer dest, long destOffset, long sourceOffset, long size) { }


    private int cidx;
    private final LongList[] allocations;
    public UploadingBufferStream(RenderDevice device, int frames, long size) {
        this.device = device;
        WEAK_UPLOAD_LIST.add(new WeakReference<>(this));
        allocations = new LongList[frames];
        for (int i = 0; i < frames; i++) {
            allocations[i] = new LongArrayList();
        }
        segments.setLimit(size);
        buffer = device.createClientMappedBuffer(size);
    }

    private long caddr = -1;
    private long offset = 0;
    public long getUpload(Buffer destBuffer, long destOffset, int size) {
        long addr;
        if (caddr == -1 || !segments.expand(caddr, size)) {
            caddr = segments.alloc(size);//TODO: replace with allocFromLargest
            allocations[cidx].add(caddr);//Enqueue the allocation to be freed
            offset = size;
            addr = caddr;
            batchedFlushes.add(caddr);
        } else {//Could expand the allocation so just update it
            addr = caddr + offset;
            offset += size;
        }

        batchedCopies.add(new Batch(destBuffer, destOffset, addr, size));

        return buffer.clientAddress() + addr;
    }


    public void commit() {
        for (long offset : batchedFlushes) {
            device.flush(buffer, offset, (int)segments.getSize(offset));
        }
        batchedFlushes.clear();
        device.barrier(GL_CLIENT_MAPPED_BUFFER_BARRIER_BIT);
        for (var batch : batchedCopies) {
            device.copyBuffer(buffer, batch.dest, batch.sourceOffset, batch.destOffset, batch.size);
        }
        batchedCopies.clear();
        device.barrier(GL_BUFFER_UPDATE_BARRIER_BIT);
    }

    public void delete() {
        buffer.delete();
    }



    private void tick() {
        //if (batchedCopies.size() != 0) {
        //    //throw new IllegalStateException("Upload buffer has uncommitted batches before tick");
        //    System.err.println("Upload buffer has uncommitted batches before tick");
        //    commit();
        //}
        //Need to free all of the next allocations
        cidx = (cidx+1)%allocations.length;
        for (long addr : allocations[cidx]) {
            segments.free(addr);
        }
        allocations[cidx].clear();
        caddr = -1;
    }

    private static final List<WeakReference<UploadingBufferStream>> WEAK_UPLOAD_LIST = new LinkedList<>();
    public static void TickAllUploadingStreams() {//Should be called at the very end of the frame
        var iter = WEAK_UPLOAD_LIST.iterator();
        while (iter.hasNext()) {
            var ref = iter.next().get() ;
            if (ref != null) {
                ref.tick();
            } else {
                iter.remove();
            }
        }
    }
}
