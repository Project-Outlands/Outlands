package net.minecraftforge.fml.common.discovery;

import java.io.File;
import java.util.List;
import java.util.jar.Attributes;

/**
 * What the early scan concluded about one source: the jar or directory a class load can come from.
 *
 * @param modType the manifest's {@code ModType}, {@code FML} when it declares none. Survives only to ignore jars built
 *                for a different loader; it is deliberately <em>not</em> an input of the LWJGL2 rewrite decision, see
 *                {@link #modernMetadata}
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
        boolean modernMetadata,
        boolean hasMixinManifestAttributes,
        boolean mixinTweakerForceMod,
        boolean coreModContainsMod,
        String coremod,
        String tweaker) {

    public DiscoveredMod {
        file = file.getAbsoluteFile();
        modIds = List.copyOf(modIds);
    }

    public boolean isDirectoryBased() {
        return file.isDirectory();
    }

}
