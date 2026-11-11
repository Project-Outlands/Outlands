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
    public static void attach(int shaderId, IResourceManager manager, ResourceLocation source,
                              int glType, String sourcePack) {
        ResourceLocation module = new ResourceLocation(source.getNamespace(), source.getPath() + MODULE_SUFFIX);
        IResource resource;
        try {
            resource = manager.getResource(module);
        } catch (IOException | RuntimeException e) {
            // No module: the ordinary case for anything that is not precompiled. The GLSL path handles it.
            return;
        }

        String modulePack = resource.getResourcePackName();
        if (sourcePack != null && !sourcePack.equals(modulePack)) {
            // A pack replaced the source and did not ship a module, so this one describes different GLSL.
            LOGGER.debug("ignoring {} because it came from {} while the shader came from {}",
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
