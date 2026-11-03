package net.minecraftforge.fml.common.discovery;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.Attributes;

import javax.annotation.Nullable;

import net.minecraftforge.fml.relauncher.Side;

/**
 * What the early scan concluded about one source: the jar or directory a class load can come from.
 *
 * @param modType the manifest's {@code ModType}, {@code FML} when it declares none. Survives only to ignore jars built
 *                for a different loader; it is deliberately <em>not</em> an input of the LWJGL2 rewrite decision, see
 *                {@link #modernMetadata}
 * @param mixins the mixin configurations the jar declares in its {@code jar} section, in declaration order; empty when
 *               it declares none
 * @param modernMetadata the jar's {@code mcmod.info} carries a {@code jar} section, i.e. it opted into the current
 *                metadata schema. Such a jar was written against the current environment, which offers no LWJGL2 API,
 *                so its classes only call LWJGL3 and rewriting them would second-guess code that is already up to date.
 *                A directory candidate declares nothing and is the one case assumed modern: it is the output directory
 *                of the module being developed, built against that same environment
 */
public record DiscoveredMod(
        File file,
        Attributes attributes,
        List<String> modIds,
        String modType,
        List<JarDeclaration.MixinConfig> mixins,
        boolean modernMetadata,
        boolean hasMixinManifestAttributes,
        boolean mixinTweakerForceMod,
        boolean coreModContainsMod,
        String coremod,
        String tweaker) {

    public DiscoveredMod {
        file = file.getAbsoluteFile();
        modIds = List.copyOf(modIds);
        mixins = List.copyOf(mixins);
    }

    public boolean isDirectoryBased() {
        return file.isDirectory();
    }

    /**
     * @return whether the jar declares mixin configurations at all, in either place
     *
     * <p>Both places still count: the manifest attributes are read by Mixin itself, so a jar that only uses them keeps
     * working, and a jar that moved its list into {@code mcmod.info} needs no manifest key at all.
     */
    public boolean declaresMixins() {
        return hasMixinManifestAttributes || !mixins.isEmpty();
    }

    /**
     * The mixin configurations this run has to register itself, in declaration order.
     *
     * <p>Only the declared ones: a manifest {@code MixinConfigs} list is read by Mixin's own platform agent when the
     * container is prepared, so registering those again would be doing the same work twice - Mixin drops the second
     * copy by name, but the log would claim the loader did something.
     *
     * @param side the side this run is on, or {@code null} when there is no launch to judge by (a tool, a test), in
     *             which case every declaration is kept rather than guessed away
     */
    public List<String> mixinConfigsFor(@Nullable Side side) {
        if (mixins.isEmpty()) {
            return List.of();
        }
        String runningSide = side == null ? null : side.name();
        List<String> configs = new ArrayList<>(mixins.size());
        for (JarDeclaration.MixinConfig mixin : mixins) {
            if (runningSide == null || !mixin.excludes(runningSide)) {
                configs.add(mixin.config());
            }
        }
        return configs.isEmpty() ? List.of() : List.copyOf(configs);
    }

}
