package net.minecraftforge.common.config;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseError;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;
import org.tomlj.TomlVersion;

import net.minecraftforge.fml.common.FMLLog;

/**
 * TOML, via {@code tomlj}.
 *
 * <p>Reading goes through the library because a conforming TOML parser is not something to reimplement. Writing is done
 * here: the model already knows the order and the comments, and an emitter of thirty lines keeps both, whereas handing
 * the job to a library would tie the file layout to that library's idea of a nice document.
 *
 * <p>Keys are written bare when they are, well, bare keys, and quoted otherwise. That is not cosmetic: in TOML a dotted
 * key means nesting, so a property legitimately named {@code a.b} has to be quoted or it becomes a category.
 */
public final class TomlCodec implements IConfigCodec {

    @Override
    public String write(Configuration cfg) {
        StringBuilder out = new StringBuilder();
        out.append("# Configuration file\n");

        for (String categoryName : cfg.getCategoryNames()) {
            ConfigCategory category = cfg.getCategory(categoryName);
            if (category.getValues().isEmpty() && category.getChildren().isEmpty())
                continue;

            out.append('\n');
            out.append('[').append(categoryPath(categoryName)).append("]\n");
            writeCategory(category, out);
        }

        return out.toString();
    }

    private void writeCategory(ConfigCategory category, StringBuilder out) {
        for (Property property : category.getOrderedValues()) {
            writeComment(property.getComment(), out);

            out.append(key(property.getName())).append(" = ");
            if (property.isList()) {
                out.append('[');
                String[] values = property.getSavedStringList();
                for (int i = 0; i < values.length; i++) {
                    if (i > 0)
                        out.append(", ");
                    out.append(value(property.getType(), values[i]));
                }
                out.append(']');
            } else {
                out.append(value(property.getType(), property.getSavedString()));
            }
            out.append('\n');
        }

        for (ConfigCategory child : category.getChildren()) {
            out.append('\n');
            out.append('[').append(categoryPath(child.getQualifiedName())).append("]\n");
            writeCategory(child, out);
        }
    }

    private static void writeComment(String comment, StringBuilder out) {
        if (comment == null || comment.isEmpty())
            return;
        for (String line : comment.split("\r?\n"))
            out.append("# ").append(line).append('\n');
    }

    /** @return the literal as TOML wants it; the model stores everything as text */
    private static String value(Property.Type type, String literal) {
        if (literal == null)
            return "\"\"";
        if (type == Property.Type.BOOLEAN || type == Property.Type.INTEGER || type == Property.Type.DOUBLE)
            return literal;
        return string(literal);
    }

    private static String string(String text) {
        StringBuilder out = new StringBuilder(text.length() + 2).append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                default:
                    if (c < 0x20)
                        out.append(String.format("\\u%04X", (int) c));
                    else
                        out.append(c);
            }
        }
        return out.append('"').toString();
    }

    private static boolean isBare(String key) {
        if (key.isEmpty())
            return false;
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            boolean ok = c == '-' || c == '_' || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
            if (!ok)
                return false;
        }
        return true;
    }

    /** A property name is one key, even when it contains a dot: quoting keeps it from becoming nesting. */
    private static String key(String name) {
        return isBare(name) ? name : string(name);
    }

    /** A category path is dotted: each segment is a table name, so each segment is quoted on its own. */
    private static String categoryPath(String path) {
        String[] segments = path.split("\\" + Configuration.CATEGORY_SPLITTER);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < segments.length; i++) {
            if (i > 0)
                out.append('.');
            out.append(isBare(segments[i]) ? segments[i] : string(segments[i]));
        }
        return out.toString();
    }

    @Override
    public void read(Configuration cfg, File file) {
        TomlParseResult parsed;
        try {
            parsed = Toml.parse((Path) file.toPath(), TomlVersion.V1_0_0);
        } catch (Exception e) {
            FMLLog.log.error("Could not read the TOML config {}", file.getName(), e);
            return;
        }

        if (parsed.hasErrors()) {
            for (TomlParseError error : parsed.errors())
                FMLLog.log.error("Refusing to use {}, which does not parse: {}", file.getName(), error);
            return;
        }

        ConfigTree.fill(cfg, toMap(parsed), file.getName());
    }

    private static Map<String, Object> toMap(TomlTable table) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : table.entrySet())
            map.put(entry.getKey(), convert(entry.getValue()));
        return map;
    }

    private static Object convert(Object value) {
        if (value instanceof TomlTable)
            return toMap((TomlTable) value);
        if (value instanceof TomlArray) {
            TomlArray array = (TomlArray) value;
            List<Object> list = new ArrayList<>(array.size());
            for (int i = 0; i < array.size(); i++)
                list.add(convert(array.get(i)));
            return list;
        }
        return value;
    }
}
