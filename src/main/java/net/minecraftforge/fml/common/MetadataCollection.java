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
            MetadataCollection collection;
            Gson gson = new GsonBuilder().registerTypeAdapter(ArtifactVersion.class, new ArtifactVersionAdapter()).create();
            JsonElement rootElement = JsonParser.parseReader(reader);
            if (rootElement != null && rootElement.isJsonArray())
            {
                collection = new MetadataCollection();
                JsonArray jsonList = rootElement.getAsJsonArray();
                collection.modList = new ModMetadata[jsonList.size()];
                int i = 0;
                for (JsonElement mod : jsonList)
                {
                    collection.modList[i++]=gson.fromJson(mod, ModMetadata.class);
                }
            }
            else if (rootElement != null && rootElement.isJsonObject())
            {
                // Object form: an optional "modList" array plus jar level data. The array may be absent, either
                // because the file only describes the jar itself (library, provider, declarative mod) or because
                // the file describes a jar that has no @Mod class at all.
                JsonObject root = rootElement.getAsJsonObject();
                collection = gson.fromJson(root, MetadataCollection.class);
                if (collection == null)
                {
                    collection = new MetadataCollection();
                }
                JsonElement jarElement = root.get("jar");
                if (jarElement != null && jarElement.isJsonObject())
                {
                    collection.jar = JarDeclaration.parse(jarElement.getAsJsonObject(), sourceName);
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
