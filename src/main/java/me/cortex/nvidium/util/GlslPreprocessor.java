package me.cortex.nvidium.util;

import com.google.common.collect.Lists;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderDefines;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.IOException;
import java.io.Reader;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GlslPreprocessor {
    private int sourceId = 0;
    private final ShaderDefines defines;
    private static final Pattern IMPORT_REGEX = Pattern.compile("#import\\s+<(?<namespace>.*):(?<path>.*)>");
    private static final Pattern INCLUDE_REGEX = Pattern.compile("#include\\s+<(?<namespace>.*):(?<path>.*)>");
    private static final Pattern ENDIF_PREPROCESS_REGEX = Pattern.compile("\\s*#endif\\s*");

    public GlslPreprocessor(ShaderDefines defines) {
        this.defines = defines;
    }

    public String process(Identifier path) {
        List<String> source = this.processImports(path);
        this.injectDefines(source, path);

        return String.join("\n", source);
    }

    private List<String> processImports(Identifier path) {
        int currentSourceId = sourceId++;
        List<String> output = Lists.newArrayList();
        List<String> source = GlslPreprocessor.resolve(path);

        if (currentSourceId != 0) {
            output.add(String.format("#line %d %s", 1, '"' + path.toString() + '"'));
        }

        for (int i = 0; i < source.size(); i++) {
            String line = source.get(i);

            // TODO clean ?? this is dirty but GLSL compiler is unhappy of this float being an int because we inject nv_gpu_shader5
            //     minecraft:include/oit_common.glsl(24) : error C1101: ambiguous overloaded function reference "clamp(float, int, float)"
            //         (0) : gp5 float64_t clamp(float64_t, float64_t, float64_t)
            //         (0) : float clamp(float, float, float)
            if (line.equals("    return clamp(-log(max(transmittance, 0.0001)), 0, 4.0);")) {
                line = "    return clamp(-log(max(transmittance, 0.0001)), 0.0, 4.0);";
            }

            Matcher matcher = IMPORT_REGEX.matcher(line);
            if (matcher.find()) {
                Identifier importPath = Identifier.fromNamespaceAndPath(matcher.group("namespace"), matcher.group("path"));
                output.addAll(this.processImports(importPath));
                output.add(String.format("#line %d \"%s\"", i + 2, path));
                continue;
            }

            matcher = INCLUDE_REGEX.matcher(line);
            if (matcher.find()) {
                Identifier importPath = Identifier.fromNamespaceAndPath(matcher.group("namespace"), "include/" + matcher.group("path"));
                output.addAll(this.processImports(importPath));
                output.add(String.format("#line %d \"%s\"", i + 2, path));
                continue;
            }

            output.add(line);
            if (ENDIF_PREPROCESS_REGEX.matcher(line).find()) {
                // This is quite ugly, if we find a #endif, we need to reinject line count because #line are not processed in preprocessor inactive branches
                output.add(String.format("#line %d \"%s\"", i + 2, path));
            }
        }

        return output;
    }

    private void injectDefines(List<String> source, Identifier path) {
        for (int i = 0; i < source.size(); i++) {
            if (source.get(i).startsWith("#version")) {
                source.add(i + 1, "#line " + (i + 2) + " \"" + path.toString() + '"');
                source.add(i + 1, this.defines.asSourceDirectives());
                source.add(i + 1, "#extension GL_ARB_shading_language_include : enable");
                return;
            }
        }

        source.addFirst(this.defines.asSourceDirectives());
        source.addFirst("#line 1 \"" + path.toString() + '"');
        source.addFirst("#extension GL_ARB_shading_language_include : enable");
    }

    public static List<String> resolve(Identifier path) {
        ResourceManager rm = Minecraft.getInstance().getResourceManager();

        Optional<Resource> res = rm.getResource(path.withPrefix("shaders/"));
        if (res.isEmpty()) {
            throw new IllegalStateException("Failed to find shader " + path.getPath());
        }

        try {
            Reader reader = res.get().openAsReader();
            List<String> source = Lists.newArrayList();
            source.add("#error shader didn't load");
            try {
                source = reader.readAllLines();
            } catch (IOException e) {
                System.out.println("Nvidium shader reader error");
            }

            if (reader != null) {
                reader.close();
            }

            return source;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to open resource reader, wtf is going on");
        }
    }
}
