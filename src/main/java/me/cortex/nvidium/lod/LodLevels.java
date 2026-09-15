package me.cortex.nvidium.lod;

import me.cortex.nvidium.Nvidium;

public final class LodLevels {
    public static final int MAX = 4;

    private LodLevels() {}

    public static int choose(int chebyshevChunks) {
        if (!Nvidium.config.lodEnabled()) {
            return 0;
        }
        int start = Math.max(8, Nvidium.config.lodStartChunks());
        if (chebyshevChunks < start) {
            return 0;
        }
        int lod = 1;
        int span = start;
        while (lod < MAX && chebyshevChunks >= span * 2) {
            lod++;
            span *= 2;
        }
        return lod;
    }

    public static int chebyshev(int ax, int ay, int az, int bx, int by, int bz) {
        return Math.max(Math.abs(ax - bx), Math.max(Math.abs(ay - by), Math.abs(az - bz)));
    }
}
