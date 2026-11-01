package net.minecraftforge.common.config;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import net.minecraftforge.fml.common.FMLLog;

/**
 * JSON, via the Gson that ships with the game.
 *
 * <p>JSON is included for completeness, not for hand editing: it has no comments, so the annotations that carry the
 * documentation for every other format have nowhere to go, and a human staring at a flat JSON document learns nothing
 * about what a key does. It is here because a format in the enum that nobody can read would be worse than a format with
 * fewer features.
 *
 * <p>Numbers are written as the property's own type so that reading the file back yields the same model rather than
 * everything becoming a double.
 */
public final class JsonCodec implements IConfigCodec {

    @Override
    public String write(Configuration cfg) {
        JsonObject root = new JsonObject();

        for (String categoryName : cfg.getCategoryNames()) {
            // Only the roots: children are written inside their parent, or the nesting would be lost.
            if (categoryName.contains(Configuration.CATEGORY_SPLITTER))
                continue;
            writeCategory(cfg.getCategory(categoryName), root);
        }

        return new GsonBuilder().setPrettyPrinting().create().toJson(root) + "\n";
    }

    /** Nested categories are written below their own names, so {@code general.advanced} nests twice. */
    private void writeCategory(ConfigCategory category, JsonObject parent) {
        JsonObject object = new JsonObject();

        for (Property property : category.getOrderedValues()) {
            if (property.isList()) {
                JsonArray array = new JsonArray();
                for (String element : property.getSavedStringList())
                    array.add(json(property.getType(), element));
                object.add(property.getName(), array);
            } else {
                object.add(property.getName(), json(property.getType(), property.getSavedString()));
            }
        }

        for (ConfigCategory child : category.getChildren())
            writeCategory(child, object);

        parent.add(category.getName(), object);
    }

    private static JsonElement json(Property.Type type, String literal) {
        if (literal == null)
            return new JsonPrimitive("");
        if (type == Property.Type.BOOLEAN)
            return new JsonPrimitive(Boolean.parseBoolean(literal));
        if (type == Property.Type.INTEGER) {
            try {
                return new JsonPrimitive(Long.parseLong(literal.trim()));
            } catch (NumberFormatException e) {
                return new JsonPrimitive(literal);
            }
        }
        if (type == Property.Type.DOUBLE) {
            try {
                return new JsonPrimitive(Double.parseDouble(literal.trim()));
            } catch (NumberFormatException e) {
                return new JsonPrimitive(literal);
            }
        }
        return new JsonPrimitive(literal);
    }

    @Override
    public void read(Configuration cfg, File file) {
        try {
            String text = ConfigFile.read(file);
            if (text == null)
                return;

            JsonElement parsed = new JsonParser().parse(text);
            if (!parsed.isJsonObject()) {
                FMLLog.log.error("Refusing to use {}: the document is not an object of categories", file.getName());
                return;
            }

            ConfigTree.fill(cfg, toMap(parsed.getAsJsonObject()), file.getName());
        } catch (IOException | RuntimeException e) {
            FMLLog.log.error("Could not read the JSON config {}", file.getName(), e);
        }
    }

    private static Map<String, Object> toMap(JsonObject object) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : object.entrySet())
            map.put(entry.getKey(), convert(entry.getValue()));
        return map;
    }

    private static Object convert(JsonElement element) {
        if (element.isJsonObject())
            return toMap(element.getAsJsonObject());
        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            List<Object> list = new ArrayList<>(array.size());
            for (JsonElement child : array)
                list.add(convert(child));
            return list;
        }
        if (element.isJsonNull())
            return null;

        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (primitive.isBoolean())
            return primitive.getAsBoolean();
        if (primitive.isNumber()) {
            // Gson hands back a lazily parsed number; the model wants the literal kind, so ask for it explicitly.
            double asDouble = primitive.getAsDouble();
            long asLong = primitive.getAsLong();
            return asDouble == asLong ? (Object) asLong : (Object) asDouble;
        }
        return primitive.getAsString();
    }
}
