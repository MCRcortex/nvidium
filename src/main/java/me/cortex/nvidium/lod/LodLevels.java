package me.cortex.nvidium.lod;

import me.cortex.nvidium.Nvidium;
import net.minecraft.core.SectionPos;

public final class LodLevels {
    public static final int MAX = 4;

    private LodLevels() {}

    public static int choose(int chebyshevChunks) {
        if (!Nvidium.config.lodEnabled()) {
            return 0;
        }
        int start = fullMeshChunks();
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

    public static int fullMeshChunks() {
        return Math.max(8, Nvidium.config.lodStartChunks());
    }

    public static int forSection(long sectionKey, int camCX, int camCZ) {
        return choose(chebyshevXZ(SectionPos.x(sectionKey), SectionPos.z(sectionKey), camCX, camCZ));
    }

    public static int chebyshevXZ(int ax, int az, int bx, int bz) {
        return Math.max(Math.abs(ax - bx), Math.abs(az - bz));
    }

    public static int chebyshev(int ax, int ay, int az, int bx, int by, int bz) {
        return Math.max(Math.abs(ax - bx), Math.max(Math.abs(ay - by), Math.abs(az - bz)));
    }
}
