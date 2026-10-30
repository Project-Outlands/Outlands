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
    private static final Set<String> KNOWN_KEYS =
            Set.of("schemaVersion", "license", "loadOrder", "providers", "providerVersions");

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

    private final int schemaVersion;
    private final String license;
    private final LoadOrder loadOrder;
    private final Map<String, String> providers;
    private final Map<String, List<String>> providerVersions;
    private final Map<String, String> unknown;

    private JarDeclaration(int schemaVersion, String license, LoadOrder loadOrder, Map<String, String> providers,
                           Map<String, List<String>> providerVersions, Map<String, String> unknown) {
        this.schemaVersion = schemaVersion;
        this.license = license;
        this.loadOrder = loadOrder;
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
        String versionText = asString(json.get("schemaVersion"));
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

        String license = asString(json.get("license"));
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

        String orderText = asString(json.get("loadOrder"));
        LoadOrder loadOrder = LoadOrder.parse(orderText);
        if (loadOrder == null) {
            // Rejecting the whole jar over a typo would be disproportionate; falling back silently would hide it.
            throw new LoaderException("The jar section in the mcmod.info of " + sourceName
                    + " declares an unknown \"loadOrder\": \"" + orderText.trim() + "\". Expected one of: first, normal, last.");
        }

        Map<String, String> providers = parseProviders(json.get("providers"), sourceName);
        return new JarDeclaration(schemaVersion, license.trim(), loadOrder, providers,
                parseProviderVersions(json.get("providerVersions"), providers, sourceName), unknown);
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
            String className = asString(entry.getValue());
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
                String text = asString(version);
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

    private static String asString(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        // Numbers are accepted for schemaVersion so that "1" and 1 mean the same thing; anything structured is not.
        return element.isJsonPrimitive() ? element.getAsString() : element.toString();
    }

    /** @return the declared schema version, never below {@link #CURRENT_SCHEMA} */
    public int schemaVersion() {
        return schemaVersion;
    }

    /** @return the declared licence, never blank */
    public String license() {
        return license;
    }

    /** @return where this jar wants to sit in coremod discovery order */
    public LoadOrder loadOrder() {
        return loadOrder;
    }

    /**
     * @return the language providers this jar declares, as language name to adapter class name, in declaration order;
     *         never {@code null}, empty when nothing is declared
     */
    public Map<String, String> providers() {
        return providers;
    }

    /**
     * @return the language versions the providers of this jar can serve, as language name to versions, in declaration
     *         order; never {@code null}, empty when nothing is declared
     */
    public Map<String, List<String>> providerVersions() {
        return providerVersions;
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
                + (providers.isEmpty() ? "" : ", providers=" + providers.keySet())
                + (providerVersions.isEmpty() ? "" : ", providerVersions=" + providerVersions)
                + (unknown.isEmpty() ? "" : ", unknown=" + unknown.keySet()) + "]";
    }
}
