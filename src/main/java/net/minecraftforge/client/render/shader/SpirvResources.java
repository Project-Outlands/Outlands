package net.minecraftforge.client.render.shader;

import net.minecraft.client.resources.IResource;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.render.vulkan.shader.SpirvReflection;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Finds a shader module that was shipped beside its source, and decides whether to trust it.
 *
 * <p><b>Where modules live.</b> Next to the source, in the source's own namespace: {@code blit.fsh} and
 * {@code blit.fsh.spv}. That makes the rule one rule for every origin - the game's assets, a shader pack, a mod's
 * resource pack - and it lets a pack ship its own precompiled modules, which is the point of compiling ahead of time.
 * The loader's own jar is just the lowest-priority pack in that stack.</p>
 *
 * <p><b>When a module is stale.</b> Only one situation produces it: a pack replaced the source but did not ship a
 * module, so the resource stack falls through to ours. The resource system already knows the answer - it reports which
 * pack each resource came from - so the module is used only when both came from the same pack. No hashing, no index,
 * no second place to keep in step.</p>
 *
 * <p><b>When a shipped module is wrong.</b> If the module is beside the source, from the same pack, and is not a
 * usable module for that stage, the shader is failed with a message naming the file. It is deliberately not compiled
 * from the GLSL instead: a mod that ships a module has said which one it wants run, and quietly compiling something
 * else turns a clear authoring error into a mystery about which shader is in effect.</p>
 */
public final class SpirvResources {

    private static final Logger LOGGER = LogManager.getLogger("Outlands|render");

    /** Suffix appended to the source's path, so the module sits beside it. */
    private static final String MODULE_SUFFIX = ".spv";
    /**
     * Suffix of the file that says which source text a module stands in for.
     *
     * <p><b>What the pack name cannot express.</b> A module normally describes the source beside it, and the resource
     * system's pack name is enough to know that they travel together. But this project replaces some of the game's
     * shaders: the module is compiled from a hand-written Vulkan GLSL file that is deliberately <i>not</i> shipped as
     * the source, because a GL context that loaded it would be handed {@code #version 450}. So for those, module and
     * source come from different packs on the stack - measured: the source from the loader's own file pack and the
     * module from {@code Default} - and the pack rule alone rejects them.
     *
     * <p>Recording the hash of the source text the module was written to replace answers the question the pack name
     * was standing in for: "is this module about the text we were just handed?" It is, when the hashes match; it is
     * not when a shader pack replaced the source, which is the case the rule exists to catch. A pack that ships its
     * own module beside its own source still passes on the pack-name rule and needs no hash at all.</p>
     */
    private static final String SOURCE_HASH_SUFFIX = ".sha1";

    private SpirvResources() {}

    /**
     * Looks for a module for the shader that was just handed {@code source}'s text.
     *
     * <p>Never throws: a missing resource manager entry is the normal case, and a broken one is reported and turned into
     * a failed shader rather than an exception in the middle of shader loading.</p>
     *
     * @param shaderId  the shader object, as returned by {@code glCreateShader}
     * @param manager   the resource manager the source itself came through
     * @param source    the resource the GLSL was read from
     * @param glType    the stage the caller asked for
     * @param sourcePack the pack that supplied the source, as reported by the resource system
     */
    public static void attach(int shaderId, IResourceManager manager, ResourceLocation source, int glType,
                              String sourcePack, String sourceText) {
        ResourceLocation module = new ResourceLocation(source.getNamespace(), source.getPath() + MODULE_SUFFIX);
        IResource resource;
        try {
            resource = manager.getResource(module);
        } catch (IOException | RuntimeException e) {
            // No module: the ordinary case for anything that is not precompiled. The GLSL path handles it.
            return;
        }

        String modulePack = resource.getResourcePackName();
        if (sourcePack != null && !sourcePack.equals(modulePack) && !replacesSource(manager, module, sourceText)) {
            // Either a pack replaced the source and shipped no module of its own, or this module cannot say which text
            // it replaces. Both mean the GLSL beside it is the truth, so the module is not used.
            LOGGER.debug("not using {}: it came from {} while the shader came from {}, and does not record this text",
                    module, modulePack, sourcePack);
            return;
        }

        byte[] bytes;
        try (InputStream in = new BufferedInputStream(resource.getInputStream())) {
            bytes = in.readAllBytes();
        } catch (IOException e) {
            GlShaders.failShader(shaderId, "cannot read " + module + ": " + e);
            LOGGER.error("cannot read shader module {}: {}", module, e.toString());
            return;
        }

        String problem = validate(bytes, glType);
        if (problem != null) {
            GlShaders.failShader(shaderId, module + " " + problem);
            LOGGER.error("shader module {} {}", module, problem);
            return;
        }
        GlShaders.attachModule(shaderId, bytes);
    }

    /**
     * Whether the module says it replaces exactly this source text.
     *
     * <p>Read from the {@code .sha1} file beside the module, written by the build at the same time as the module. A
     * missing or unreadable file answers no: a module whose provenance cannot be established is not used, which is the
     * same answer the pack rule gives and for the same reason.</p>
     */
    private static boolean replacesSource(IResourceManager manager, ResourceLocation module, String sourceText) {
        ResourceLocation hash = new ResourceLocation(module.getNamespace(), module.getPath() + SOURCE_HASH_SUFFIX);
        try (InputStream in = manager.getResource(hash).getInputStream()) {
            String recorded = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
            String actual = SourceHashes.sha1(sourceText);
            if (recorded.equalsIgnoreCase(actual)) {
                return true;
            }
            LOGGER.info("{} does not describe the shader it sits beside (recorded {}, actual {}), so the GLSL will be"
                    + " compiled instead", module, recorded, actual);
            return false;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /**
     * Whether a module may stand in for a stage.
     *
     * @return null when it may, otherwise a phrase describing what is wrong with it
     */
    public static String validate(byte[] module, int glType) {
        if (module == null || module.length < 20 || module.length % 4 != 0) {
            return "is not a SPIR-V module (bad length)";
        }
        int model = SpirvReflection.executionModel(module);
        if (model < 0) {
            return "declares no entry point, so it is not a usable module";
        }
        int expected = glType == GlShaders.VERTEX_SHADER
                ? SpirvReflection.EXECUTION_VERTEX : SpirvReflection.EXECUTION_FRAGMENT;
        if (model != expected) {
            return "is a " + stageName(model) + " module but was shipped for a " + stageName(expected) + " shader";
        }
        return null;
    }

    private static String stageName(int executionModel) {
        return switch (executionModel) {
            case SpirvReflection.EXECUTION_VERTEX -> "vertex";
            case SpirvReflection.EXECUTION_FRAGMENT -> "fragment";
            default -> "execution-model-" + executionModel;
        };
    }
}
