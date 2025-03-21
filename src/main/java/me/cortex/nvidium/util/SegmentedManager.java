package me.cortex.nvidium.util;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongRBTreeSet;

import java.util.Random;

//TODO: replace the LongAVLTreeSet with a custom implementation that doesnt cause allocations when searching
// and see if something like a RBTree is any better
public class SegmentedManager {
    public static final long SIZE_LIMIT = -1;

    private final int ADDR_BITS = 34;//This gives max size per allocation of 2^30 and max address of 2^39
    private final int SIZE_BITS = 64 - ADDR_BITS;
    private final long SIZE_MSK = (1L<<SIZE_BITS)-1;
    private final long ADDR_MSK = (1L<<ADDR_BITS)-1;
    private final LongRBTreeSet FREE = new LongRBTreeSet();//Size Address
    private final LongRBTreeSet TAKEN = new LongRBTreeSet();//Address Size

    private long sizeLimit = Long.MAX_VALUE;
    private long totalSize;
    //Flags
    public boolean resized;//If the required memory of the entire buffer grew

    public long getSize() {
        return totalSize;
    }
    /*
    public long allocFromLargest(int size) {//Allocates from the largest avalible block, this is useful for expanding later on

    }*/

    public long alloc(int size) {
        if (size == 0) throw new IllegalArgumentException();
        //This is stupid, iterator is not inclusive
        var iter = FREE.iterator(((long) size << ADDR_BITS)-1);
        if (!iter.hasNext()) {//No free space for allocation
            //Create new allocation
            resized = true;
            long addr = totalSize;
            if (totalSize+size>sizeLimit) {
                return SIZE_LIMIT;
            }
            totalSize += size;
            TAKEN.add((addr<<SIZE_BITS)|((long) size));
            return addr;
        } else {
            long slot = iter.nextLong();
            iter.remove();
            if ((slot >>> ADDR_BITS) == size) {//If the allocation and slot is the same size, just add it to the taken
                TAKEN.add((slot<<SIZE_BITS)|(slot >>> ADDR_BITS));
            } else {
                TAKEN.add(((slot&ADDR_MSK)<<SIZE_BITS)|size);
                FREE.add((((slot >>> ADDR_BITS)-size)<<ADDR_BITS)|((slot&ADDR_MSK)+size));
            }
            resized = false;
            return slot&ADDR_MSK;
        }
    }

    public int free(long addr) {//Returns size of freed memory
        addr &= ADDR_MSK;//encase addr stores shit in its upper bits
        var iter = TAKEN.iterator(addr<<SIZE_BITS);//Dont need to include -1 as size != 0
        long slot = iter.nextLong();
        if (slot>>SIZE_BITS != addr) {
            throw new IllegalStateException();
        }
        long size = slot&SIZE_MSK;
        iter.remove();

        //Note: if there is a previous entry, it means that it is guaranteed for the ending address to either
        // be the addr, or indicate a free slot that needs to be merged
        if (iter.hasPrevious()) {
            long prevSlot = iter.previousLong();
            long endAddr = (prevSlot>>>SIZE_BITS) + (prevSlot&SIZE_MSK);
            if (endAddr != addr) {//It means there is a free slot that needs to get merged into
                long delta = (addr - endAddr);
                FREE.remove((delta<<ADDR_BITS)|endAddr);//Free the slot to be merged into
                //Generate a new slot to get put into FREE
                slot = (endAddr<<SIZE_BITS) | ((slot&SIZE_MSK) + delta);
            }
            iter.nextLong();//Need to reset the iter into its state
        }//If there is no previous it means were at the start of the buffer, we might need to merge with block 0 if we are not block 0
        else if (!FREE.isEmpty()) {// if free is not empty it means we must merge with block of free starting at 0
            if (FREE.remove(addr<<ADDR_BITS)) {//Attempt to remove block 0, this is very dodgy as it assumes block zero is 0 addr n size
                slot = addr + size;//slot at address 0 and size of 0 block + new block
            }
        }

        //If there is a next element it is guarenteed to either be the next block, or indicate that there is
        // a block that needs to be merged into
        if (iter.hasNext()) {
            long nextSlot = iter.nextLong();
            long endAddr = (slot>>>SIZE_BITS) + (slot&SIZE_MSK);
            if (endAddr != nextSlot>>>SIZE_BITS) {//It means there is a memory block to be merged in FREE
                long delta = ((nextSlot>>>SIZE_BITS) - endAddr);
                FREE.remove((delta<<ADDR_BITS)|endAddr);
                slot = (slot&(ADDR_MSK<<SIZE_BITS)) | ((slot&SIZE_MSK) + delta);
            }
        }// if there is no next block it means that we have reached the end of the allocation sections and we can shrink the buffer
        else {
            resized = true;
            totalSize -= (slot&SIZE_MSK);
            return (int) size;
        }

        resized = false;
        //Need to swap around the slot to be in FREE format
        slot = (slot>>>SIZE_BITS) | (slot<<ADDR_BITS);
        FREE.add(slot);//Add the free slot into segments
        return (int) size;
    }



    //Attempts to expand an allocation, returns true on success
    public boolean expand(long addr, int extra) {
        addr &= ADDR_MSK;//encase addr stores shit in its upper bits
        var iter = TAKEN.iterator(addr<<SIZE_BITS);
        if (!iter.hasNext()) {
            return false;
        }
        long slot = iter.nextLong();
        if (slot>>SIZE_BITS != addr) {
            throw new IllegalStateException();
        }
        long updatedSlot = (slot & (ADDR_MSK << SIZE_BITS)) | ((slot & SIZE_MSK) + extra);
        resized = false;
        if (iter.hasNext()) {
            long next = iter.nextLong();
            long endAddr = (slot>>>SIZE_BITS)+(slot&SIZE_MSK);
            long delta = (next>>>SIZE_BITS) - endAddr;
            if (extra <= delta) {
                FREE.remove((delta<<ADDR_BITS)|endAddr);//Should assert this
                iter.previousLong();//FOR SOME REASON NEED  TO DO IT TWICE I HAVE NO IDEA WHY
                iter.previousLong();
                iter.remove();//Remove the allocation so it can be updated
                TAKEN.add(updatedSlot);//Update the taken allocation
                if (extra != delta) {//More space than needed, need to add a new FREE block
                    FREE.add(((delta-extra)<<ADDR_BITS)|(endAddr+extra));
                }
                //else There is exactly enough free space, so removing the free block and updating the allocation is enough
                return true;
            } else {
                return false;//Not enough room to expand
            }
        } else {//We are at the end of the buffer, we can expand as we like
            if (totalSize+extra>sizeLimit)//If expanding and we would exceed the size limit, dont resize
                return false;
            iter.remove();
            TAKEN.add(updatedSlot);
            totalSize += extra;
            resized = true;
            return true;
        }
    }

    public long getSize(long addr) {
        addr &= ADDR_MSK;
        var iter = TAKEN.iterator(addr << SIZE_BITS);
        if (!iter.hasNext())
            throw new IllegalArgumentException();
        long slot = iter.nextLong();
        if (slot>>SIZE_BITS != addr) {
            throw new IllegalStateException();
        }
        return slot&SIZE_MSK;
    }


    public static void main(String[] args) {
        /*
        {
            SegmentedManager m = new SegmentedManager();
            long a = m.alloc(10);
            long b = m.alloc(11);
            long c = m.alloc(1);
            System.out.println(m.expand(a, 1));
            m.free(b);
            System.out.println(m.expand(a, 1));
            System.out.println(m.expand(a, 10));
            System.out.println(m.expand(a, 1));
            m.free(a);
            m.free(c);
            System.out.println(m.getSize());
        }*/
        /*
        Random r = new Random(32);
        SegmentedManager m = new SegmentedManager();
        LongList l = new LongArrayList();
        for (int i = 0; i < 5; i++) {
            if (r.nextBoolean() || l.isEmpty()) {
                long a = m.alloc(r.nextInt(1000) + 1);
                l.add(a);
            } else {
                m.free(l.removeLong(r.nextInt(l.size())));
            }
        }

        for (long a : l) {
            m.free(a);
        }
        System.out.println(m.getSize());
        */


        for (int j = 0; j < 10000; j++) {
            Random r = new Random(j);
            SegmentedManager m = new SegmentedManager();
            LongList l = new LongArrayList();
            for (int i = 0; i < 5000; i++) {
                int ac = r.nextInt(3);
                if ( ac ==0 || l.isEmpty()) {
                    long a = m.alloc(r.nextInt(1000) + 1);
                    l.add(a);
                } else if (ac == 1) {
                    m.free(l.removeLong(r.nextInt(l.size())));
                } else {
                    m.expand(l.getLong(r.nextInt(l.size())), r.nextInt(10) + 1);
                }
            }

            for (long a : l) {
                m.free(a);
            }
            if (m.getSize() != 0) {
                System.out.println(j);
                return;
            }
        }
    }

    public void setLimit(long size) {
        this.sizeLimit = size;
    }
}
