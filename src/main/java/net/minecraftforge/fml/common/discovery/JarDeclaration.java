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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
    private static final Set<String> KNOWN_KEYS = Set.of("schemaVersion", "license");

    private final int schemaVersion;
    private final String license;
    private final Map<String, String> unknown;

    private JarDeclaration(int schemaVersion, String license, Map<String, String> unknown) {
        this.schemaVersion = schemaVersion;
        this.license = license;
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

        return new JarDeclaration(schemaVersion, license.trim(), unknown);
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
                + (unknown.isEmpty() ? "" : ", unknown=" + unknown.keySet()) + "]";
    }
}
