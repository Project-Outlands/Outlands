/*
 * Minecraft Forge
 * Copyright (c) 2016-2020.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation version 2.1.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */

package net.minecraftforge.fml.common.discovery;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.Attributes;

import javax.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraftforge.fml.common.LoaderException;

/**
 * The {@code jar} section of a {@code mcmod.info} object: what a jar says about itself, as opposed to what it says
 * about the mods inside it.
 *
 * <p>Exists because jar-level facts had nowhere to live but the manifest, whose keys are the ones the loader reads
 * before it has parsed any metadata. A jar whose manifest yields a mod id never even had its metadata read, so a
 * jar-level declaration could not describe it at all; parsing this section unconditionally fixes that.
 *
 * <p>Declaring the section opts the jar into the current schema, so the fields that make a file usable as a
 * declaration are mandatory there: a section without {@code license} is rejected rather than defaulted, because a
 * missing licence is exactly the thing a downstream consumer cannot guess. Files without the section are untouched -
 * they predate it and are read as before.
 */
public final class JarDeclaration {

    /** The schema this loader writes. A section declaring a higher one is read as this one, with a warning. */
    public static final int CURRENT_SCHEMA = 1;

    /** Keys this version understands. Anything else is reported once and ignored, never rejected. */
    /**
     * What this loader understands in the {@code jar} section. Anything else is reported back to the author.
     *
     * <p>Maven coordinates are deliberately absent even though {@link #containedDeps} refers to libraries by identity:
     * a coordinate is what a jar says about <em>itself</em>, in its own manifest ({@code Maven-Artifact}), and the jar
     * that carries one is often a plain library with no {@code mcmod.info} at all. Requiring metadata from those would
     * make a metadata section mandatory for exactly the jars that have no author left to add it.
     */
    private static final Set<String> KNOWN_KEYS =
            Set.of("schemaVersion", "license", "loadOrder", "providers", "providerVersions", "containedDeps",
                    "entrypoints", "side", "at", "coremod", "alsoMod", "mixins");

    /**
     * Manifest attribute the {@code side} key replaces, still read for jars that declare no section.
     *
     * <p>Spelled here because this class defines what the section replaces; the manifest constants that used to own
     * these names are now aliases of them.
     */
    public static final Attributes.Name MANIFEST_SIDE = new Attributes.Name("ModSide");

    /** Keys understood inside one {@code mixins} entry. Anything else is reported once and ignored. */
    private static final Set<String> MIXIN_KEYS = Set.of("config", "side");

    /** Manifest attribute the {@code at} key replaces: a space-separated list of the same paths. */
    public static final Attributes.Name MANIFEST_ACCESS_TRANSFORMERS = new Attributes.Name("FMLAT");

    /** Manifest attribute the {@code coremod} key replaces: the loading plugin class of this jar. */
    public static final Attributes.Name MANIFEST_CORE_MOD = new Attributes.Name("FMLCorePlugin");

    /**
     * Where this jar wants to sit in the order jars are opened for coremod discovery.
     *
     * <p>Three positions, not a number: what existing packs express with a {@code !} or {@code 000_} filename prefix is
     * "before everything", and a score would make "why is this third" unanswerable from the file listing alone. The
     * order still falls back to the filename, so a jar that declares nothing keeps the position it has today.
     */
    public enum LoadOrder {
        /** Ahead of jars that declare nothing. */
        FIRST(-1),
        /** The default: filename order. */
        NORMAL(0),
        /** Behind jars that declare nothing. */
        LAST(1);

        private final int rank;

        LoadOrder(int rank) {
            this.rank = rank;
        }

        public int rank() {
            return rank;
        }

        static LoadOrder parse(String value) {
            if (value == null || value.isBlank()) {
                return NORMAL;
            }
            return switch (value.trim().toLowerCase(java.util.Locale.ENGLISH)) {
                case "first" -> FIRST;
                case "normal", "default" -> NORMAL;
                case "last" -> LAST;
                default -> null;
            };
        }
    }

    /**
     * Which side a jar belongs to.
     *
     * <p>{@link #BOTH} is the default: load it wherever. The other two make the jar disappear on the opposite side
     * completely - it is neither loaded as a coremod nor offered as a mod candidate - so a mod depending on it reports
     * a missing dependency instead of loading against half of it.
     */
    public enum Side {
        BOTH,
        CLIENT,
        SERVER;

        /** @return the side a value names, {@link #BOTH} for "any"/"both"/absent, or {@code null} when unrecognised */
        public static Side parse(String value) {
            if (value == null || value.isBlank()) {
                return BOTH;
            }
            return switch (value.trim().toUpperCase(java.util.Locale.ENGLISH)) {
                case "BOTH", "ANY", "*" -> BOTH;
                case "CLIENT" -> CLIENT;
                case "SERVER" -> SERVER;
                default -> null;
            };
        }

        /** @return whether a jar for this side must not be loaded while running as {@code runningSide} */
        public boolean excludes(String runningSide) {
            return this != BOTH && !name().equals(runningSide);
        }
    }

    private final int schemaVersion;
    private final String license;
    private final LoadOrder loadOrder;
    private final Side side;
    private final List<String> accessTransformers;
    @Nullable
    private final String coreMod;
    private final boolean alsoMod;
    private final List<ContainedDep> containedDeps;
    private final List<MixinConfig> mixins;
    private final Map<String, String> entrypoints;
    private final Map<String, String> providers;
    private final Map<String, List<String>> providerVersions;
    private final Map<String, String> unknown;


    /**
     * What a jar says a bundled entry is.
     *
     * <p>This is per entry rather than per jar because one jar routinely bundles both kinds at once: a prerequisite mod
     * that has to be discovered, and a plain library that only needs to be on the classpath. The old {@code NonModDeps}
     * manifest attribute was one flag for the whole jar, so such a jar could not say either thing correctly.
     */
    public enum ContainedDepKind {
        /** A mod in its own right: extracted into the mods directory and discovered like any other. The default. */
        MOD,
        /**
         * A library: put on the classpath and not scanned for mods.
         *
         * <p>Deliberately unchecked - nothing looks inside a jar marked this way, so an entry that does contain a mod is
         * the declaring author's mistake and is not second-guessed here.
         */
        LIB;

        static ContainedDepKind parse(String value) {
            if (value == null || value.isBlank()) {
                return MOD;
            }
            return switch (value.trim().toUpperCase(java.util.Locale.ENGLISH)) {
                case "MOD" -> MOD;
                case "LIB", "LIBRARY" -> LIB;
                default -> null;
            };
        }
    }

    /**
     * One bundled jar inside this jar.
     *
     * <p>{@code id} and {@code version} exist so that two jars can bundle different versions of the same dependency and
     * the loader can say which one it is looking at. The legacy {@code ContainedDeps} manifest key carries neither - only
     * a path - which is why the same runtime conflict used to be unavoidable there.
     */
    public record ContainedDep(String id, String version, String path, ContainedDepKind kind) {

        public ContainedDep(String id, String version, String path) {
            this(id, version, path, ContainedDepKind.MOD);
        }

        /** @return the entry's file name, which is what the loader sees on disk once it is extracted */
        public String fileName() {
            return path.substring(path.lastIndexOf('/') + 1);
        }
    }

    /**
     * One mixin configuration the jar carries.
     *
     * <p>{@code side} is the reason this is an object rather than the manifest's comma-separated list: a jar that runs
     * on both sides still tends to carry configs that only make sense on one of them, and a config registered on the
     * wrong side is not merely useless - its target classes may not exist there at all, so a {@code required} mixin in
     * it turns into a failure at the first transform.
     */
    public record MixinConfig(String config, Side side) {

        public MixinConfig {
            java.util.Objects.requireNonNull(config, "config");
            java.util.Objects.requireNonNull(side, "side");
        }

        public MixinConfig(String config) {
            this(config, Side.BOTH);
        }

        /** @return whether this configuration must not be registered while running as {@code runningSide} */
        public boolean excludes(String runningSide) {
            return side.excludes(runningSide);
        }
    }

    /**
     * Finds the declaration for a bundled entry.
     *
     * <p>Matched by full path or by file name: an entry may be looked up as {@code META-INF/libraries/x.jar} when the
     * declaration said {@code x.jar}, and both spellings name the same thing inside the jar.
     *
     * @return the declared entry, or {@code null} when the declaration does not mention {@code path} at all
     */
    @Nullable
    public ContainedDep depFor(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        for (ContainedDep dep : containedDeps) {
            if (dep.path().equals(path) || dep.fileName().equals(name)) {
                return dep;
            }
        }
        return null;
    }

    private JarDeclaration(int schemaVersion, String license, LoadOrder loadOrder, Side side,
                           List<String> accessTransformers, @Nullable String coreMod, boolean alsoMod,
                           List<ContainedDep> containedDeps, List<MixinConfig> mixins,
                           Map<String, String> entrypoints, Map<String, String> providers,
                           Map<String, List<String>> providerVersions, Map<String, String> unknown) {
        this.schemaVersion = schemaVersion;
        this.license = license;
        this.loadOrder = loadOrder;
        this.side = side;
        this.accessTransformers = accessTransformers;
        this.coreMod = coreMod;
        this.alsoMod = alsoMod;
        this.containedDeps = containedDeps;
        this.mixins = mixins;
        this.entrypoints = entrypoints;
        this.providers = providers;
        this.providerVersions = providerVersions;
        this.unknown = unknown;
    }

    /**
     * Reads a {@code jar} object.
     *
     * @throws LoaderException if the section is present but unusable: no {@code schemaVersion}, or no {@code license}
     */
    public static JarDeclaration parse(JsonObject json, String sourceName) {
        String versionText = MetadataJson.optionalScalarText(json.get("schemaVersion"), where(sourceName), "schemaVersion");
        if (versionText == null || versionText.isBlank()) {
            throw new LoaderException("The jar section in the mcmod.info of " + sourceName
                    + " does not declare \"schemaVersion\". Add \"schemaVersion\": " + CURRENT_SCHEMA + ".");
        }

        int schemaVersion;
        try {
            schemaVersion = Integer.parseInt(versionText.trim());
        } catch (NumberFormatException e) {
            throw new LoaderException("The jar section in the mcmod.info of " + sourceName
                    + " declares a non-numeric \"schemaVersion\": \"" + versionText + "\".", e);
        }

        String license = MetadataJson.optionalText(json.get("license"), where(sourceName), "license");
        if (license == null || license.isBlank()) {
            throw new LoaderException("The jar section in the mcmod.info of " + sourceName
                    + " does not declare \"license\". Supply an SPDX identifier and/or a link to the licence text.");
        }

        Map<String, String> unknown = Collections.emptyMap();
        for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
            if (KNOWN_KEYS.contains(entry.getKey())) {
                continue;
            }
            if (unknown.isEmpty()) {
                unknown = new LinkedHashMap<>();
            }
            unknown.put(entry.getKey(), entry.getValue().toString());
        }

        String orderText = MetadataJson.optionalText(json.get("loadOrder"), where(sourceName), "loadOrder");
        LoadOrder loadOrder = LoadOrder.parse(orderText);
        if (loadOrder == null) {
            // Rejecting the whole jar over a typo would be disproportionate; falling back silently would hide it.
            throw new LoaderException("The jar section in the mcmod.info of " + sourceName
                    + " declares an unknown \"loadOrder\": \"" + orderText.trim() + "\". Expected one of: first, normal, last.");
        }

        String sideText = MetadataJson.optionalText(json.get("side"), where(sourceName), "side");
        Side side = Side.parse(sideText);
        if (side == null) {
            throw new LoaderException("The jar section in the mcmod.info of " + sourceName
                    + " declares an unknown \"side\": \"" + sideText.trim() + "\". Expected one of: both, client, server.");
        }

        Map<String, String> providers = parseProviders(json.get("providers"), sourceName);
        return new JarDeclaration(schemaVersion, license.trim(), loadOrder, side,
                parseAccessTransformers(json.get("at"), sourceName),
                parseCoreMod(json.get("coremod"), sourceName),
                parseBoolean(json.get("alsoMod"), "alsoMod", sourceName),
                parseContainedDeps(json.get("containedDeps"), sourceName),
                parseMixins(json.get("mixins"), sourceName, side),
                parseEntrypoints(json.get("entrypoints"), sourceName), providers,
                parseProviderVersions(json.get("providerVersions"), providers, sourceName), unknown);
    }

    /**
     * The loading plugin a jar declares, or {@code null}.
     *
     * <p>The declaration wins over the manifest attribute, so a jar whose section names a different class than its
     * manifest does gets the one it declared.
     */
    @Nullable
    public static String coreModOf(@Nullable Attributes attributes, @Nullable JarDeclaration declaration) {
        if (declaration != null && declaration.coreMod != null) {
            return declaration.coreMod;
        }
        if (attributes == null) {
            return null;
        }
        String value = attributes.getValue(MANIFEST_CORE_MOD);
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** @return the sentence subject of every message about this file's {@code jar} section */
    private static String where(String sourceName) {
        return "The jar section in the mcmod.info of " + sourceName;
    }

    @Nullable
    private static String parseCoreMod(JsonElement element, String sourceName) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        String value = MetadataJson.optionalText(element, where(sourceName), "coremod");
        if (value == null) {
            return null;
        }
        if (value.isBlank()) {
            throw new LoaderException("The jar section in the mcmod.info of " + sourceName
                    + " declares \"coremod\" without a class name. Supply the full name of an IFMLLoadingPlugin.");
        }
        return value.trim();
    }

    private static boolean parseBoolean(JsonElement element, String key, String sourceName) {
        if (element == null || element.isJsonNull()) {
            return false;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
            throw new LoaderException("The jar section in the mcmod.info of " + sourceName
                    + " declares \"" + key + "\" as something other than a boolean: " + element);
        }
        return element.getAsBoolean();
    }

    /**
     * Which side a jar is for, preferring its own declaration over the manifest attribute it replaces.
     *
     * <p>A manifest value nobody recognises is read as {@link Side#BOTH} with a warning: the attribute predates any
     * validation, and refusing to start an otherwise working pack over a misspelt side would be a worse trade than
     * loading the jar the way it was loaded before the value existed.
     */
    public static Side sideOf(@Nullable Attributes attributes, @Nullable JarDeclaration declaration) {
        if (declaration != null) {
            return declaration.side;
        }
        String value = attributes == null ? null : attributes.getValue(MANIFEST_SIDE);
        Side side = Side.parse(value);
        if (side == null) {
            net.minecraftforge.fml.common.FMLLog.log.warn("The manifest declares ModSide: {}, which is neither BOTH,"
                    + " CLIENT nor SERVER; reading it as BOTH", value);
            return Side.BOTH;
        }
        return side;
    }

    /**
     * The access transformer files a jar carries, as paths relative to {@code META-INF/}.
     *
     * <p>Same precedence as {@link #sideOf}: the declaration first, then the space-separated manifest attribute. The
     * list shape is what the manifest could not express - an AT file name with a space in it stayed unrepresentable
     * until now.
     */
    public static List<String> accessTransformersOf(@Nullable Attributes attributes, @Nullable JarDeclaration declaration) {
        if (declaration != null && !declaration.accessTransformers.isEmpty()) {
            return declaration.accessTransformers;
        }
        String value = attributes == null ? null : attributes.getValue(MANIFEST_ACCESS_TRANSFORMERS);
        return splitPaths(value);
    }

    private static List<String> splitPaths(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        List<String> paths = new ArrayList<>();
        for (String path : value.trim().split("\\s+")) {
            if (!path.isBlank()) {
                paths.add(path);
            }
        }
        return List.copyOf(paths);
    }

    /**
     * Reads {@code at}: the access transformer files this jar carries, relative to {@code META-INF/}.
     *
     * <p>An array element is one path and is never split, which is the one thing the manifest key could not express: a
     * file name containing a space. A single string is still accepted and split on whitespace, because that is the
     * shape the key it replaces had - porting a jar should not require rewriting the value it already carries.
     */
    private static List<String> parseAccessTransformers(JsonElement element, String sourceName) {
        if (element == null || element.isJsonNull()) {
            return List.of();
        }
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            return splitPaths(element.getAsString());
        }
        if (!element.isJsonArray()) {
            throw new LoaderException("The jar section in the mcmod.info of " + sourceName
                    + " declares \"at\" as neither a string nor an array of strings.");
        }
        List<String> paths = new ArrayList<>();
        for (JsonElement entry : element.getAsJsonArray()) {
            if (entry == null || !entry.isJsonPrimitive() || !entry.getAsJsonPrimitive().isString()) {
                throw new LoaderException("The jar section in the mcmod.info of " + sourceName
                        + " declares a non-string entry in \"at\": " + entry);
            }
            String path = entry.getAsString().trim();
            if (path.isEmpty()) {
                throw new LoaderException("The jar section in the mcmod.info of " + sourceName
                        + " declares an empty entry in \"at\"; each entry is one file under META-INF/.");
            }
            paths.add(path);
        }
        return paths.isEmpty() ? List.of() : List.copyOf(paths);
    }

    /**
     * Reads {@code entrypoints}: declared mod names, each with the class that will speak for it at runtime.
     *
     * <p>The key is the mod id, so a jar written in a language without mod classes can still present named mods that
     * dependency ordering, disabling and per-mod event delivery all work on. The value is the only place a class name
     * appears: everything the author would otherwise write as annotations sits behind it.
     */
    private static Map<String, String> parseEntrypoints(JsonElement element, String sourceName) {
        if (element == null || element.isJsonNull()) {
            return Collections.emptyMap();
        }
        if (!element.isJsonObject()) {
            throw new LoaderException("The jar section in the mcmod.info of " + sourceName
                    + " declares \"entrypoints\" as something other than an object. Expected {\"<modid>\": \"<entry point class>\"}.");
        }
        Map<String, String> entrypoints = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
            String modId = entry.getKey() == null ? "" : entry.getKey().trim();
            if (modId.isEmpty()) {
                throw new LoaderException("The jar section in the mcmod.info of " + sourceName
                        + " declares an entrypoint under an empty mod id.");
            }
            String className = MetadataJson.optionalText(entry.getValue(), where(sourceName), "entrypoints." + modId);
            if (className == null || className.isBlank()) {
                throw new LoaderException("The jar section in the mcmod.info of " + sourceName + " declares the entrypoint "
                        + "\"" + modId + "\" without a class name. The value must be the full name of an "
                        + "IDeclarativeEntrypoint implementation.");
            }
            entrypoints.put(modId, className.trim());
        }
        return entrypoints.isEmpty() ? Collections.emptyMap() : Collections.unmodifiableMap(entrypoints);
    }

    /**
     * Reads {@code containedDeps}: libraries bundled inside this jar, each with an optional identity.
     *
     * <p>Structured rather than the manifest's space-separated list because the manifest form cannot express which
     * library a path is or which version it is, so two jars bundling different versions of one library cannot be told
     * apart. A path with no {@code id} takes its name from the file, and no {@code version} means {@code "0"} - the
     * same reading the legacy form gets, so a jar can migrate one entry at a time.
     */
    private static List<ContainedDep> parseContainedDeps(JsonElement element, String sourceName) {
        if (element == null || element.isJsonNull()) {
            return List.of();
        }
        if (!element.isJsonArray()) {
            throw new LoaderException("The jar section in the mcmod.info of " + sourceName
                    + " declares \"containedDeps\" as something other than an array. Expected [{\"path\": \"...\"}, ...].");
        }
        List<ContainedDep> deps = new ArrayList<>();
        for (JsonElement entry : element.getAsJsonArray()) {
            if (entry == null || !entry.isJsonObject()) {
                throw new LoaderException("The jar section in the mcmod.info of " + sourceName
                        + " declares a containedDep that is not an object.");
            }
            JsonObject object = entry.getAsJsonObject();
            String path = MetadataJson.optionalText(object.get("path"), where(sourceName), "containedDeps.path");
            if (path == null || path.isBlank()) {
                throw new LoaderException("The jar section in the mcmod.info of " + sourceName
                        + " declares a containedDep without a \"path\". The path is the entry inside this jar.");
            }
            path = path.trim();
            if (!path.endsWith(".jar")) {
                // The only reason a path is here is to be put on the classpath, and only a jar can be.
                throw new LoaderException("The jar section in the mcmod.info of " + sourceName
                        + " declares the containedDep \"" + path + "\", which is not a .jar file.");
            }
            String name = path.substring(path.lastIndexOf('/') + 1);
            name = name.substring(0, name.length() - ".jar".length());

            String id = MetadataJson.optionalScalarText(object.get("id"), where(sourceName), "containedDeps.id");
            id = id == null || id.isBlank() ? name : id.trim();
            String version = MetadataJson.optionalScalarText(object.get("version"), where(sourceName), "containedDeps.version");
            version = version == null || version.isBlank() ? "0" : version.trim();
            String kindText = MetadataJson.optionalText(object.get("kind"), where(sourceName), "containedDeps.kind");
            ContainedDepKind kind = ContainedDepKind.parse(kindText);
            if (kind == null) {
                throw new LoaderException("The jar section in the mcmod.info of " + sourceName + " declares an unknown"
                        + " \"kind\" for the contained dependency \"" + path + "\": \"" + kindText.trim()
                        + "\". Expected one of: MOD, LIB.");
            }

            deps.add(new ContainedDep(id, version, path, kind));
        }
        return deps.isEmpty() ? List.of() : List.copyOf(deps);
    }

    /**
     * Reads {@code mixins}: the mixin configurations this jar carries.
     *
     * <p>One object per config, for the same reason every other list in the section is structured: the manifest key it
     * replaces was a comma-separated string with no room for a second fact, and the fact that matters is the side.
     *
     * <p>The config file itself is not read here - it is Mixin's to parse, and Mixin reports a bad one in its own
     * words. What is checked here is what the loader can notice while the section is in hand: that the value is a
     * config name at all, and that it is not declared twice. Whether the file is inside the jar is checked by
     * {@link #verifyMixinConfigs}, which needs the jar open.
     */
    private static List<MixinConfig> parseMixins(JsonElement element, String sourceName, Side jarSide) {
        if (element == null || element.isJsonNull()) {
            return List.of();
        }
        if (!element.isJsonArray()) {
            throw new LoaderException("The jar section in the mcmod.info of " + sourceName
                    + " declares \"mixins\" as " + MetadataJson.describeType(element)
                    + ". Expected an array of mixin configurations, as in [{\"config\": \"mymod.mixins.json\"}].");
        }
        List<MixinConfig> mixins = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (JsonElement entry : element.getAsJsonArray()) {
            if (entry == null || !entry.isJsonObject()) {
                throw new LoaderException("The jar section in the mcmod.info of " + sourceName
                        + " declares a mixin entry that is not an object. Expected {\"config\": \"mymod.mixins.json\"}.");
            }
            JsonObject object = entry.getAsJsonObject();
            for (String key : object.keySet()) {
                if (!MIXIN_KEYS.contains(key)) {
                    // Reported, not rejected: a newer schema may add a field, and refusing to start over one is the
                    // worse trade.
                    net.minecraftforge.fml.common.FMLLog.log.warn("The jar section in the mcmod.info of {} declares the"
                            + " mixin key \"{}\", which this loader does not understand", sourceName, key);
                }
            }
            String config = MetadataJson.optionalText(object.get("config"), where(sourceName), "mixins.config");
            if (config == null || config.isBlank()) {
                throw new LoaderException("The jar section in the mcmod.info of " + sourceName
                        + " declares a mixin entry without \"config\". The value is the configuration's resource name.");
            }
            config = config.trim();
            if (!config.endsWith(".json")) {
                // Mixin registers only names ending in .json and silently ignores the rest, so a config without the
                // suffix would do nothing at all and say nothing about it.
                throw new LoaderException("The jar section in the mcmod.info of " + sourceName + " declares the mixin"
                        + " configuration \"" + config + "\", which does not end in .json.");
            }
            if (!seen.add(config)) {
                // Two entries for one config can only repeat or disagree about the side, and Mixin would register it
                // once anyway.
                throw new LoaderException("The jar section in the mcmod.info of " + sourceName + " declares the mixin"
                        + " configuration \"" + config + "\" more than once.");
            }
            String sideText = MetadataJson.optionalText(object.get("side"), where(sourceName), "mixins.side");
            Side side = Side.parse(sideText);
            if (side == null) {
                throw new LoaderException("The jar section in the mcmod.info of " + sourceName + " declares an unknown"
                        + " \"side\" for the mixin configuration \"" + config + "\": \"" + sideText.trim()
                        + "\". Expected one of: both, client, server.");
            }
            if (side != Side.BOTH && jarSide != Side.BOTH && side != jarSide) {
                net.minecraftforge.fml.common.FMLLog.log.warn("The jar section in the mcmod.info of {} is for the {}, but"
                        + " the mixin configuration {} is declared for the {}; it will never be registered", sourceName,
                        jarSide.name().toLowerCase(java.util.Locale.ENGLISH), config,
                        side.name().toLowerCase(java.util.Locale.ENGLISH));
            }
            mixins.add(new MixinConfig(config, side));
        }
        return mixins.isEmpty() ? List.of() : List.copyOf(mixins);
    }

    /**
     * Reads the {@code providers} object: language name to the class that can construct mods written in it.
     *
     * <p>Malformed entries are rejected rather than skipped. A provider section exists so that a mod declaring
     * {@code modLanguage: "scala"} can be built; dropping half of it silently would leave that mod failing with "no
     * provider is registered" while the jar visibly declares one, which is the least debuggable outcome available.
     */
    private static Map<String, String> parseProviders(JsonElement element, String sourceName) {
        if (element == null || element.isJsonNull()) {
            return Collections.emptyMap();
        }
        if (!element.isJsonObject()) {
            throw new LoaderException("The jar section in the mcmod.info of " + sourceName
                    + " declares \"providers\" as something other than an object. Expected {\"<language>\": \"<adapter class>\"}.");
        }
        Map<String, String> providers = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
            String language = entry.getKey() == null ? "" : entry.getKey().trim();
            if (language.isEmpty()) {
                throw new LoaderException("The jar section in the mcmod.info of " + sourceName
                        + " declares a provider under an empty language name. The key is the language mods will ask for.");
            }
            String className = MetadataJson.optionalText(entry.getValue(), where(sourceName), "providers." + language);
            if (className == null || className.isBlank()) {
                throw new LoaderException("The jar section in the mcmod.info of " + sourceName
                        + " declares a provider for language \"" + language
                        + "\" without a class name. The value must be the full name of an "
                        + "ILanguageAdapter implementation.");
            }
            providers.put(language, className.trim());
        }
        return providers.isEmpty() ? Collections.emptyMap() : Collections.unmodifiableMap(providers);
    }

    /**
     * Reads {@code providerVersions}: for each language this jar provides, the language versions that provider can serve.
     *
     * <p>This is the other half of {@code modLanguageVersion} on a mod entry. Without it the constraint has nothing to
     * compare against, which is why a mod that declares one only gets a warning today.
     *
     * <p>Versions for a language the same jar does not provide are rejected rather than kept: they describe a provider
     * that does not exist, so they would silently never be consulted.
     */
    private static Map<String, List<String>> parseProviderVersions(JsonElement element, Map<String, String> providers,
                                                                   String sourceName) {
        if (element == null || element.isJsonNull()) {
            return Collections.emptyMap();
        }
        if (!element.isJsonObject()) {
            throw new LoaderException("The jar section in the mcmod.info of " + sourceName
                    + " declares \"providerVersions\" as something other than an object. Expected {\"<language>\": [\"<version>\", ...]}.");
        }
        Map<String, List<String>> versions = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
            String language = entry.getKey() == null ? "" : entry.getKey().trim();
            if (!providers.containsKey(language)) {
                throw new LoaderException("The jar section in the mcmod.info of " + sourceName
                        + " declares providerVersions for language \"" + language
                        + "\", but this jar declares no provider for that language. Versions describe a provider, so both go together.");
            }
            JsonElement value = entry.getValue();
            if (value == null || !value.isJsonArray()) {
                throw new LoaderException("The jar section in the mcmod.info of " + sourceName
                        + " declares providerVersions for \"" + language + "\" as something other than an array of versions.");
            }
            List<String> supported = new ArrayList<>();
            for (JsonElement version : value.getAsJsonArray()) {
                String text = MetadataJson.optionalScalarText(version, where(sourceName), "providerVersions." + language);
                if (text == null || text.isBlank()) {
                    throw new LoaderException("The jar section in the mcmod.info of " + sourceName
                            + " declares a blank version for the \"" + language + "\" provider.");
                }
                supported.add(text.trim());
            }
            if (supported.isEmpty()) {
                // An empty list is indistinguishable from "declares nothing" once read, so accepting it would make the
                // jar look like it constrained something while having no effect.
                throw new LoaderException("The jar section in the mcmod.info of " + sourceName
                        + " declares an empty providerVersions list for \"" + language
                        + "\". Remove the key instead, which means the same thing explicitly.");
            }
            versions.put(language, List.copyOf(supported));
        }
        return versions.isEmpty() ? Collections.emptyMap() : Collections.unmodifiableMap(versions);
    }

    /** @return the declared schema version, never below {@link #CURRENT_SCHEMA} */
    public int schemaVersion() {
        return schemaVersion;
    }

    /** @return the declared licence, never blank */
    public String license() {
        return license;
    }

    /**
     * @return the mods this jar declares without mod classes, as mod id to entry point class, in declaration order; never
     *         {@code null}, empty when it declares none
     */
    public Map<String, String> entrypoints() {
        return entrypoints;
    }

    /** @return where this jar wants to sit in coremod discovery order */
    public LoadOrder loadOrder() {
        return loadOrder;
    }

    /** @return the side this jar belongs to, {@link Side#BOTH} when it says nothing */
    public Side side() {
        return side;
    }

    /**
     * @return the access transformer files this jar carries, as paths relative to {@code META-INF/}, in declaration
     *         order; never {@code null}, empty when it declares none
     */
    public List<String> accessTransformers() {
        return accessTransformers;
    }

    /** @return the loading plugin class this jar declares, or {@code null} when it declares none */
    @Nullable
    public String coreMod() {
        return coreMod;
    }

    /**
     * @return whether this jar is a mod as well as whatever it loads early, i.e. it must be offered to the mod
     *         discovery like any other jar once its loading plugin has been handled
     */
    public boolean alsoMod() {
        return alsoMod;
    }

    /**
     * @return the language providers this jar declares, as language name to adapter class name, in declaration order;
     *         never {@code null}, empty when nothing is declared
     */
    /**
     * @return the libraries this jar bundles, in declaration order; never {@code null}, empty when it bundles none
     */
    public List<ContainedDep> containedDeps() {
        return containedDeps;
    }

    /**
     * @return the mixin configurations this jar carries, in declaration order; never {@code null}, empty when it
     *         declares none
     */
    public List<MixinConfig> mixins() {
        return mixins;
    }

    /**
     * @return the language versions the providers of this jar can serve, as language name to versions, in declaration
     *         order; never {@code null}, empty when nothing is declared
     */
    public Map<String, List<String>> providerVersions() {
        return providerVersions;
    }

    public Map<String, String> providers() {
        return providers;
    }

    /**
     * Refuses declared mixin configurations that are not inside the jar.
     *
     * <p>The one check that cannot be made while parsing, because it needs the jar open - and the one worth making:
     * {@code Mixins.addConfiguration} catches a config it cannot read and logs a line, so a typo otherwise produces a
     * game that starts with half of a mod's patches missing and nothing said while the jar was being scanned.
     *
     * @param sourceName the file the section came from, for the message
     * @param resourceExists whether a resource with that name is present in the jar
     * @throws LoaderException when a declared configuration is not there
     */
    public void verifyMixinConfigs(String sourceName, java.util.function.Predicate<String> resourceExists) {
        for (MixinConfig mixin : mixins) {
            if (!resourceExists.test(mixin.config())) {
                throw new LoaderException("The jar section in the mcmod.info of " + sourceName + " declares the mixin"
                        + " configuration \"" + mixin.config() + "\", which is not in the jar.");
            }
        }
    }

    /** @return keys this loader does not understand, with their JSON text, for a single warning */
    public Map<String, String> unknownKeys() {
        return Collections.unmodifiableMap(unknown);
    }

    /** @return the known keys of this schema version, for diagnostics */
    public static Set<String> knownKeys() {
        return new LinkedHashSet<>(KNOWN_KEYS);
    }

    @Override
    public String toString() {
        return "JarDeclaration[schema=" + schemaVersion + ", license=" + license
                + (loadOrder == LoadOrder.NORMAL ? "" : ", loadOrder=" + loadOrder.name().toLowerCase(java.util.Locale.ENGLISH))
                + (containedDeps.isEmpty() ? "" : ", containedDeps=" + containedDeps.size())
                + (mixins.isEmpty() ? "" : ", mixins=" + mixins.size())
                + (entrypoints.isEmpty() ? "" : ", entrypoints=" + entrypoints.keySet())
                + (providers.isEmpty() ? "" : ", providers=" + providers.keySet())
                + (providerVersions.isEmpty() ? "" : ", providerVersions=" + providerVersions)
                + (unknown.isEmpty() ? "" : ", unknown=" + unknown.keySet()) + "]";
    }
}
