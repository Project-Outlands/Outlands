package net.minecraftforge.fml.common;

import net.minecraftforge.fml.common.versioning.VersionParser;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Covers where a mod's dependencies come from.
 *
 * <p>{@code useDependencyInformation} exists because {@code mcmod.info} duplicated the annotation: a jar had to say
 * which of the two it meant. A jar that declares the {@code jar} section has already said it, so its entry is read
 * without the flag - which is the point of the new format, and the reason the flag is deprecated.
 */
class DependencySourceTest {

    private static ModMetadata requirement(String target) {
        ModMetadata metadata = new ModMetadata();
        metadata.requiredMods = new java.util.HashSet<>();
        metadata.requiredMods.add(VersionParser.parseVersionReference(target));
        return metadata;
    }

    @Test
    void withoutADeclarationTheFlagStillDecides() {
        // Every jar written before the section: the entry is a copy of the annotation, so honouring it is opt-in.
        assertEquals(FMLModContainer.DependencySource.ANNOTATION, FMLModContainer.dependencySource(null, requirement("foo@[1.0,)"), false));
        assertEquals(FMLModContainer.DependencySource.ENTRY_LISTS, FMLModContainer.dependencySource(null, flag(requirement("foo@[1.0,)")), false));
    }

    @Test
    void theDeclaredFormOutranksBothListsAndTheAnnotation() {
        ModMetadata metadata = requirement("foo@[1.0,)");
        metadata.declaredDependencies = ModDependency.parse(
                new com.google.gson.JsonParser().parse("[{\"on\":\"bar\",\"required\":true}]"),
                "test");

        assertEquals(FMLModContainer.DependencySource.DECLARED, FMLModContainer.dependencySource(collectionJar(), metadata, false));
    }

    @Test
    void aDeclarationMakesTheEntryAuthoritativeOnItsOwn() {
        assertEquals(FMLModContainer.DependencySource.ENTRY_LISTS, FMLModContainer.dependencySource(collectionJar(), requirement("foo@[1.0,)"), false),
                "a jar that declares the section does not also need useDependencyInformation");
        assertEquals(FMLModContainer.DependencySource.ENTRY_LISTS, FMLModContainer.dependencySource(collectionJar(), flag(requirement("foo@[1.0,)")), false),
                "declaring the flag as well is harmless, just useless");
    }

    @Test
    void anEntryThatSaysNothingFallsBackToTheAnnotation() {
        // "declared nothing" and "did not migrate this part" are the same JSON. A mod that adopted the section for its
        // containedDeps and left its dependencies in the annotation must not silently lose them.
        assertEquals(FMLModContainer.DependencySource.ANNOTATION, FMLModContainer.dependencySource(collectionJar(), new ModMetadata(), false));
    }

    @Test
    void aDependencyListIsEnoughWithoutTheRequirements() {
        // "before/after" only: an entry may order itself against others without requiring anything.
        ModMetadata metadata = new ModMetadata();
        metadata.dependencies.add(VersionParser.parseVersionReference("bar@[2.0,)"));

        assertEquals(FMLModContainer.DependencySource.ENTRY_LISTS, FMLModContainer.dependencySource(collectionJar(), metadata, false));
    }

    @Test
    void anExplicitNullListIsStillNothing() {
        // "requiredMods": null reaches the field as null, and asking a null how big it is would turn a harmless entry
        // into a crash during discovery.
        ModMetadata metadata = new ModMetadata();
        metadata.requiredMods = null;
        metadata.dependencies = null;

        assertEquals(FMLModContainer.DependencySource.ANNOTATION, FMLModContainer.dependencySource(collectionJar(), metadata, false));
    }

    @Test
    void useMetadataFalseStillWins() {
        // @Mod(useMetadata = false) is a mod saying its annotation is the truth about itself. That is a per-mod
        // statement, so it outranks the jar-level one even in the new format.
        assertEquals(FMLModContainer.DependencySource.ANNOTATION, FMLModContainer.dependencySource(collectionJar(), requirement("foo@[1.0,)"), true));
    }

    private static ModMetadata flag(ModMetadata metadata) {
        metadata.useDependencyInformation = true;
        return metadata;
    }

    /** A metadata collection whose file declares the jar section, which is what {@code dependencySource} looks at. */
    private static MetadataCollection collectionJar() {
        return MetadataCollection.from(new java.io.ByteArrayInputStream(
                "{\"jar\":{\"schemaVersion\":1,\"license\":\"MIT\"}}".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                "test.jar");
    }
}
