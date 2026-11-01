package net.minecraftforge.common.config;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;

import org.snakeyaml.engine.v2.api.Dump;
import org.snakeyaml.engine.v2.api.DumpSettings;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.common.FlowStyle;
import org.snakeyaml.engine.v2.schema.CoreSchema;

import net.minecraftforge.fml.common.FMLLog;

/**
 * YAML, via {@code snakeyaml-engine}.
 *
 * <p>The engine is the YAML 1.2 implementation rather than the older JavaBeans one, for two reasons that both matter
 * here. Its schema is fixed, so {@code yes}/{@code no}/{@code on}/{@code off} stay strings and {@code 010} is ten
 * rather than eight - otherwise the same file would mean different things in different mods. And it cannot construct
 * arbitrary classes from tags at all, so a config file cannot be used to instantiate anything.
 *
 * <p>Values are written through the library's dumper but the document layout is ours: one category per mapping, entries
 * in the order the model gives, comments from the annotations.
 */
public final class YamlCodec implements IConfigCodec {

    @Override
    public String write(Configuration cfg) {
        StringBuilder out = new StringBuilder("# Configuration file\n");

        for (String categoryName : cfg.getCategoryNames()) {
            ConfigCategory category = cfg.getCategory(categoryName);
            writeCategory(category, out, "");
        }

        return out.toString();
    }

    private void writeCategory(ConfigCategory category, StringBuilder out, String indent) {
        if (out.length() > 0 && !out.toString().endsWith("\n\n"))
            out.append('\n');

        out.append(indent).append(key(category.getQualifiedName())).append(":\n");

        for (Property property : category.getOrderedValues()) {
            writeComment(property.getComment(), out, indent + "  ");
            out.append(indent).append("  ").append(key(property.getName())).append(':');

            if (property.isList()) {
                String[] values = property.getSavedStringList();
                if (values.length == 0) {
                    out.append(" []\n");
                } else {
                    out.append('\n');
                    for (String element : values)
                        out.append(indent).append("  - ").append(scalar(property.getType(), element)).append('\n');
                }
            } else {
                out.append(' ').append(scalar(property.getType(), property.getSavedString())).append('\n');
            }
        }

        for (ConfigCategory child : category.getChildren())
            writeCategory(child, out, indent + "  ");
    }

    private static void writeComment(String comment, StringBuilder out, String indent) {
        if (comment == null || comment.isEmpty())
            return;
        for (String line : comment.split("\r?\n"))
            out.append(indent).append("# ").append(line).append('\n');
    }

    private static String key(String name) {
        return bare(name) ? name : quote(name);
    }

    /** Scalars keep their type by being written as YAML's own literals, not as strings with quotes around them. */
    private static String scalar(Property.Type type, String literal) {
        if (literal == null)
            return "''";
        if (type == Property.Type.BOOLEAN || type == Property.Type.INTEGER || type == Property.Type.DOUBLE)
            return literal;
        return quote(literal);
    }

    private static boolean bare(String text) {
        if (text.isEmpty())
            return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!(c == '-' || c == '_' || c == '.' || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')))
                return false;
        }
        return true;
    }

    private static String quote(String text) {
        // The dumper knows how to escape; it does not know where in the document the value goes, which is why only the
        // scalar goes through it.
        Dump dump = new Dump(DumpSettings.builder().setDefaultFlowStyle(FlowStyle.FLOW).build());
        String dumped = dump.dumpToString(text);
        return dumped.endsWith("\n") ? dumped.substring(0, dumped.length() - 1) : dumped;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void read(Configuration cfg, File file) {
        try {
            String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            LoadSettings settings = LoadSettings.builder()
                    .setSchema(new CoreSchema())
                    .setLabel(file.getName())
                    .build();

            Object loaded = new Load(settings).loadFromString(text);
            if (loaded == null)
                return;

            if (!(loaded instanceof Map)) {
                FMLLog.log.error("Refusing to use {}: the document is a {}, not a mapping of categories",
                        file.getName(), loaded.getClass().getSimpleName());
                return;
            }

            ConfigTree.fill(cfg, (Map<Object, Object>) loaded, file.getName());
        } catch (Exception e) {
            FMLLog.log.error("Could not read the YAML config {}", file.getName(), e);
        }
    }
}
