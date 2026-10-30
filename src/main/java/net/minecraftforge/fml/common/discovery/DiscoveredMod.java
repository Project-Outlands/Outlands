package net.minecraftforge.fml.common.discovery;

import java.io.File;
import java.util.List;
import java.util.jar.Attributes;

/**
 * What the early scan found out about one source.
 *
 * <p>Two kinds of "what is this" are recorded and they are not the same thing: {@code modType} comes from the manifest
 * and describes what the Mixin/Forge tooling should treat the jar as, while {@code declarationType} comes from the
 * {@code jar} section of the jar's own {@code mcmod.info} and is what the loader's assembly obeys. Keeping both means a
 * consumer can tell a jar that merely looks like a mod from one that says it is not.
 */
public record DiscoveredMod(
        File file,
        Attributes attributes,
        List<String> modIds,
        String modType,
        String declarationType,
        boolean hasMixinManifestAttributes,
        boolean mixinTweakerForceMod,
        boolean coreModContainsMod,
        String coremod,
        String tweaker) {

    public DiscoveredMod {
        file = file.getAbsoluteFile();
        modIds = List.copyOf(modIds);
        declarationType = declarationType == null ? DeclarationTypes.MOD : declarationType;
    }

    public boolean isDirectoryBased() {
        return file.isDirectory();
    }

}
