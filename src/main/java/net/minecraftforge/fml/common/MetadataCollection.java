/*
 * Minecraft Forge
 * Copyright (c) 2016-2020.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation version 2.1
 * of the License.
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

package net.minecraftforge.fml.common;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

import net.minecraftforge.fml.common.discovery.JarDeclaration;
import net.minecraftforge.fml.common.versioning.ArtifactVersion;
import net.minecraftforge.fml.common.versioning.VersionParser;

import com.google.common.collect.Maps;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

import javax.annotation.Nullable;

public class MetadataCollection
{
    private ModMetadata[] modList;
    /**
     * The jar-level declaration, or {@code null} for files that predate it. Transient because it is read by hand:
     * gson would happily fill it while ignoring the keys it does not know, which is the opposite of what the section
     * needs - an unknown key has to be visible to the reader, not silently dropped.
     */
    private transient JarDeclaration jar;
    private final Map<String, ModMetadata> metadatas = Maps.newHashMap();

    public static MetadataCollection from(@Nullable InputStream inputStream, String sourceName)
    {
        if (inputStream == null)
        {
            return new MetadataCollection();
        }

        InputStreamReader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8);
        try
        {
            MetadataCollection collection = new MetadataCollection();
            Gson gson = new GsonBuilder().registerTypeAdapter(ArtifactVersion.class, new ArtifactVersionAdapter()).create();
            JsonElement rootElement = JsonParser.parseReader(reader);
            if (rootElement != null && rootElement.isJsonArray())
            {
                collection.modList = readModList(gson, rootElement.getAsJsonArray(), sourceName);
            }
            else if (rootElement != null && rootElement.isJsonObject())
            {
                // Object form: an optional "modList" array plus jar level data. The array may be absent, either
                // because the file only describes the jar itself (library, provider, declarative mod) or because
                // the file describes a jar that has no @Mod class at all.
                JsonObject root = rootElement.getAsJsonObject();
                // Read key by key rather than handing the object to gson: the jar section has to be recognised even
                // when an entry beside it is malformed, and a key of the wrong type has to be named as such instead
                // of surfacing as "this file is not valid JSON".
                JsonElement jarElement = root.get("jar");
                if (jarElement != null && !jarElement.isJsonNull())
                {
                    if (!jarElement.isJsonObject())
                    {
                        throw new LoaderException("The mcmod.info of " + sourceName + " declares \"jar\" as "
                                + describeType(jarElement) + ". Expected an object holding the jar's own metadata.");
                    }
                    collection.jar = JarDeclaration.parse(jarElement.getAsJsonObject(), sourceName);
                }
                JsonElement modListElement = root.get("modList");
                if (modListElement != null && !modListElement.isJsonNull())
                {
                    if (modListElement.isJsonArray())
                    {
                        collection.modList = readModList(gson, modListElement.getAsJsonArray(), sourceName);
                    }
                    else
                    {
                        // Reported, not rejected: "modList" is the key the format had before the jar section, so a
                        // jar that gets its shape wrong there is old metadata, not a claim to follow a new schema.
                        // The mods are still found by scanning for @Mod, which is what an absent list falls back to.
                        FMLLog.log.error("The mcmod.info of {} declares \"modList\" as {} instead of an array; reading"
                                + " the file as if it had none", sourceName, describeType(modListElement));
                    }
                }
            }
            else
            {
                // An empty file, or a bare string/number. Previously the object branch fed this to gson, which
                // returned null and the caller then dereferenced it, so an empty mcmod.info took the whole
                // discovery down instead of being ignored.
                FMLLog.log.error("The mcmod.info file in {} is neither a mod list array nor an object. It will be ignored", sourceName);
                return new MetadataCollection();
            }
            collection.parseModMetadataList();
            return collection;
        }
        catch (JsonParseException e)
        {
            FMLLog.log.error("The mcmod.info file in {} cannot be parsed as valid JSON. It will be ignored", sourceName, e);
            return new MetadataCollection();
        }
    }

    /**
     * Reads the mod entries one at a time.
     *
     * <p>One entry with a value of the wrong type used to take the whole file down: gson throws for the entry, the
     * exception reached the caller's "this is not valid JSON" handler, and everything beside it went with it -
     * including the {@code jar} section, which has nothing to do with the malformed entry and is often the only
     * reason the file has the object form at all. Naming the entry and carrying on loses strictly less.
     */
    private static ModMetadata[] readModList(Gson gson, JsonArray jsonList, String sourceName)
    {
        ModMetadata[] mods = new ModMetadata[jsonList.size()];
        for (int i = 0; i < jsonList.size(); i++)
        {
            JsonElement mod = jsonList.get(i);
            if (mod != null && !mod.isJsonNull())
            {
                try
                {
                    mods[i] = gson.fromJson(mod, ModMetadata.class);
                }
                catch (JsonParseException e)
                {
                    FMLLog.log.error("Mod entry {} in the mcmod.info of {} has a value of the wrong type and is ignored: {}",
                            i, sourceName, e.getMessage());
                }
            }
            // Reported whether the entry could be read or not: the key is misplaced either way.
            warnOnJarLevelKeyInModEntry(mod, sourceName);
        }
        return mods;
    }

    /** @return what a JSON value is, for a message that says what was found instead of what was wanted */
    private static String describeType(JsonElement element)
    {
        if (element.isJsonObject())
        {
            return "an object";
        }
        if (element.isJsonArray())
        {
            return "an array";
        }
        return element.getAsJsonPrimitive().isBoolean() ? "a boolean" : "a number";
    }

    /**
     * Reports jar-level keys written into a mod entry, where they are read by nobody. Silent would be worse: the entry
     * parses fine, the jar loads fine, and the declaration simply has no effect.
     */
    private static void warnOnJarLevelKeyInModEntry(JsonElement mod, String sourceName)
    {
        if (mod == null || !mod.isJsonObject())
        {
            return;
        }
        for (String jarLevelKey : JarDeclaration.knownKeys())
        {
            if (mod.getAsJsonObject().has(jarLevelKey))
            {
                FMLLog.log.warn("The mcmod.info of {} declares \"{}\" inside a mod entry; it is a jar-level key and has no"
                        + " effect there. Move it into the \"jar\" object.", sourceName, jarLevelKey);
            }
        }
    }

    private void parseModMetadataList()
    {
        if (modList == null)
        {
            // Object form without "modList", see from().
            return;
        }
        for (ModMetadata modMetadata : modList)
        {
            // A null element ("[null]") has no id to key on; the id-less and blank-id cases are reported by
            // the discovery code, which is the only place that knows which jar and entry they came from.
            if (modMetadata != null)
            {
                metadatas.put(modMetadata.modId, modMetadata);
            }
        }
    }

    public ModMetadata getMetadataForId(String modId, Map<String, Object> extraData)
    {
        if (!metadatas.containsKey(modId))
        {
            ModMetadata dummy = new ModMetadata();
            dummy.modId = modId;
            dummy.name = (String) extraData.get("name");
            dummy.version = (String) extraData.get("version");
            dummy.autogenerated = true;
            metadatas.put(modId, dummy);
        }
        return metadatas.get(modId);
    }

    public Set<String> getIds() {
        return metadatas.keySet();
    }

    /**
     * @return the jar-level declaration this file carries, or {@code null} when it carries none
     */
    @Nullable
    public JarDeclaration getJarDeclaration()
    {
        return jar;
    }

    public static class ArtifactVersionAdapter extends TypeAdapter<ArtifactVersion>
    {

        @Override
        public void write(JsonWriter out, ArtifactVersion value) throws IOException
        {
            // no op - we never write these out
        }

        @Override
        public ArtifactVersion read(JsonReader in) throws IOException
        {
            return VersionParser.parseVersionReference(in.nextString());
        }

    }
}
