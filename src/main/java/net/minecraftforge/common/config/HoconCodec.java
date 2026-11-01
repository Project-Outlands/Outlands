package net.minecraftforge.common.config;

import java.io.File;
import java.util.Map;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigException;
import com.typesafe.config.ConfigFactory;

import net.minecraftforge.fml.common.FMLLog;

/**
 * HOCON, via {@code typesafe-config}.
 *
 * <p>HOCON is the one format here that can pull in other files ({@code include}) and read the environment
 * ({@code ${?VAR}}). That makes it useful to write by hand and impossible to rewrite faithfully: dumping the parsed
 * result would bake those substitutions into the file, so a later change to the include or the environment would stop
 * having any effect. It is therefore read-and-materialise-once: the game writes such a file when it is missing and never
 * touches it again.
 */
public final class HoconCodec implements IConfigCodec {

    @Override
    public boolean rewriteable() {
        return false;
    }

    @Override
    public String write(Configuration cfg) {
        StringBuilder out = new StringBuilder("# Configuration file\n");

        for (String categoryName : cfg.getCategoryNames()) {
            ConfigCategory category = cfg.getCategory(categoryName);
            writeCategory(category, out, 0);
        }

        return out.toString();
    }

    private void writeCategory(ConfigCategory category, StringBuilder out, int indent) {
        String padding = "    ".repeat(indent);
        out.append('\n').append(padding).append(key(category.getQualifiedName())).append(" {\n");

        for (Property property : category.getOrderedValues()) {
            writeComment(property.getComment(), out, padding + "    ");
            out.append(padding).append("    ").append(key(property.getName())).append(" = ");

            if (property.isList()) {
                String[] values = property.getSavedStringList();
                out.append('[');
                for (int i = 0; i < values.length; i++) {
                    if (i > 0)
                        out.append(", ");
                    out.append(scalar(property.getType(), values[i]));
                }
                out.append("]\n");
            } else {
                out.append(scalar(property.getType(), property.getSavedString())).append('\n');
            }
        }

        for (ConfigCategory child : category.getChildren())
            writeCategory(child, out, indent + 1);

        out.append(padding).append("}\n");
    }

    private static void writeComment(String comment, StringBuilder out, String padding) {
        if (comment == null || comment.isEmpty())
            return;
        for (String line : comment.split("\r?\n"))
            out.append(padding).append("# ").append(line).append('\n');
    }

    private static String key(String name) {
        return bare(name) ? name : quote(name);
    }

    private static String scalar(Property.Type type, String literal) {
        if (literal == null)
            return "\"\"";
        if (type == Property.Type.BOOLEAN || type == Property.Type.INTEGER || type == Property.Type.DOUBLE)
            return literal;
        return quote(literal);
    }

    private static boolean bare(String text) {
        if (text.isEmpty())
            return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!(c == '-' || c == '_' || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')))
                return false;
        }
        return true;
    }

    private static String quote(String text) {
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
                default:
                    out.append(c);
            }
        }
        return out.append('"').toString();
    }

    @Override
    @SuppressWarnings("unchecked")
    public void read(Configuration cfg, File file) {
        try {
            Config parsed = ConfigFactory.parseFile(file).resolve();
            Object unwrapped = parsed.root().unwrapped();

            if (!(unwrapped instanceof Map)) {
                FMLLog.log.error("Refusing to use {}: the document is not a mapping of categories", file.getName());
                return;
            }

            ConfigTree.fill(cfg, (Map<?, ?>) unwrapped, file.getName());
        } catch (ConfigException e) {
            FMLLog.log.error("Could not read the HOCON config {}", file.getName(), e);
        }
    }
}
