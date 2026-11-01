package net.minecraftforge.common.config;

import java.util.List;
import java.util.Map;

import net.minecraftforge.fml.common.FMLLog;

/**
 * The one place that decides what a parsed config tree means.
 *
 * <p>Every format is reduced to nested {@code Map}s, {@code List}s and scalars and handed to this class, so a
 * {@code [section]} in TOML, an indented block in YAML and a {@code section {}} in HOCON all end up as the same
 * {@link ConfigCategory}. Anything that has no faithful model representation is refused here, once, with the path that
 * caused it - rather than being interpreted slightly differently by each codec.
 *
 * <p>The rules:
 *
 * <ul>
 *   <li>a table (map) becomes a category, nested by full path;</li>
 *   <li>a scalar becomes a property whose type follows the literal, and is narrowed to the field's type when the
 *       annotation model binds it;</li>
 *   <li>a list of scalars becomes a list property;</li>
 *   <li>a top-level scalar, an array of tables, a heterogeneous array, a date/time and {@code null} are refused: none of
 *       them can be expressed in the model without inventing a rule the CFG format would not share.</li>
 * </ul>
 */
final class ConfigTree {

    private ConfigTree() {}

    /** Fills {@code cfg} from a parsed file, complaining about anything the model cannot hold. */
    static void fill(Configuration cfg, Map<?, ?> root, String source) {
        for (Map.Entry<?, ?> entry : root.entrySet()) {
            String key = String.valueOf(entry.getKey());
            Object value = entry.getValue();

            if (value instanceof Map)
                fillCategory(cfg, key, (Map<?, ?>) value, source);
            else
                refuse(source, key, value, "a category (a table), because every entry needs one");
        }
    }

    /**
     * Walks one table. The category is created only when an entry is actually accepted, so a file whose contents were
     * all refused leaves no empty categories behind for the annotation model to fill with defaults.
     */
    private static void fillCategory(Configuration cfg, String categoryPath, Map<?, ?> values, String source) {
        for (Map.Entry<?, ?> entry : values.entrySet()) {
            String key = String.valueOf(entry.getKey());
            Object value = entry.getValue();
            String path = categoryPath + Configuration.CATEGORY_SPLITTER + key;

            if (value instanceof Map) {
                fillCategory(cfg, path, (Map<?, ?>) value, source);
                continue;
            }

            if (value instanceof List) {
                String[] scalars = scalars((List<?>) value, source, path);
                if (scalars == null)
                    continue;
                cfg.getCategory(categoryPath).put(key, new Property(key, scalars, listType((List<?>) value)));
                continue;
            }

            Property.Type type = typeOf(value);
            if (type == null) {
                refuse(source, path, value, "a scalar (string, number or boolean)");
                continue;
            }

            cfg.getCategory(categoryPath).put(key, new Property(key, literal(value), type));
        }
    }

    /** @return the elements as strings, or {@code null} when the list cannot be a list property */
    private static String[] scalars(List<?> list, String source, String path) {
        if (list.isEmpty())
            return new String[0]; // an empty list carries no type of its own; the field decides

        Class<?> kind = null;
        for (Object element : list) {
            if (element instanceof Map || element instanceof List) {
                refuse(source, path, element, "a list of scalars (arrays of tables and nested arrays are not supported)");
                return null;
            }
            Property.Type type = typeOf(element);
            if (type == null) {
                refuse(source, path, element, "a list of scalars");
                return null;
            }
            if (kind == null)
                kind = kindOf(type);
            else if (kind != kindOf(type)) {
                refuse(source, path, element, "a homogeneous list (mixed types have no single field type)");
                return null;
            }
        }

        String[] result = new String[list.size()];
        for (int i = 0; i < list.size(); i++)
            result[i] = literal(list.get(i));
        return result;
    }

    private static Property.Type listType(List<?> list) {
        for (Object element : list) {
            Property.Type type = typeOf(element);
            if (type != null)
                return type;
        }
        return Property.Type.STRING;
    }

    private static Class<?> kindOf(Property.Type type) {
        switch (type) {
            case BOOLEAN:
                return Boolean.class;
            case INTEGER:
                return Long.class;
            case DOUBLE:
                return Double.class;
            default:
                return String.class;
        }
    }

    static Property.Type typeOf(Object value) {
        if (value instanceof Boolean)
            return Property.Type.BOOLEAN;
        if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte)
            return Property.Type.INTEGER;
        if (value instanceof Double || value instanceof Float)
            return Property.Type.DOUBLE;
        if (value instanceof String)
            return Property.Type.STRING;
        return null;
    }

    /** The text of a scalar as the model stores it. */
    static String literal(Object value) {
        return String.valueOf(value);
    }

    private static void refuse(String source, String path, Object value, String expected) {
        String found = value == null ? "null" : value.getClass().getSimpleName();
        FMLLog.log.warn("Ignoring {} in {}: expected {}, found {}", path, source, expected, found);
    }
}
