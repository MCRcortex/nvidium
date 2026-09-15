package me.cortex.nvidium.persist;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;
import java.util.Locale;

public final class PersistentWorldPaths {
    private PersistentWorldPaths() {}

    public static Path resolve(ClientLevel level) {
        Minecraft mc = Minecraft.getInstance();
        String dimension = dimensionFolder(level);
        if (mc.hasSingleplayerServer() && mc.getSingleplayerServer() != null) {
            Path worldRoot = mc.getSingleplayerServer().getWorldPath(LevelResource.ROOT);
            return worldRoot.resolve("nvidium-persistent").resolve(dimension);
        }
        String server = "unknown";
        var data = mc.getCurrentServer();
        if (data != null && data.ip != null && !data.ip.isBlank()) {
            server = sanitize(data.ip);
        }
        return mc.gameDirectory.toPath().resolve("nvidium-persistent").resolve(server).resolve(dimension);
    }

    private static String dimensionFolder(ClientLevel level) {
        Identifier id = level.dimension().identifier();
        return sanitize(id.getNamespace() + "_" + id.getPath());
    }

    private static String sanitize(String raw) {
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '.') {
                out.append(c);
            } else {
                out.append('_');
            }
        }
        String cleaned = out.toString().toLowerCase(Locale.ROOT);
        return cleaned.isEmpty() ? "world" : cleaned;
    }
}
