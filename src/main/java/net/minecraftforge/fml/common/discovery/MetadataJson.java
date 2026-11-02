package net.minecraftforge.fml.common.discovery;

import javax.annotation.Nullable;

import com.google.gson.JsonElement;

import net.minecraftforge.fml.common.LoaderException;

/**
 * The type rules every hand-read key in {@code mcmod.info} follows.
 *
 * <p>Kept in one place because the two readers - the jar section and the dependency list in a mod entry - must give the
 * same answer for the same mistake. Telling an author "the jar section refuses a boolean here" and "the mod entry
 * accepts one" would be an accident of which parser was written first.
 */
public final class MetadataJson {

    private MetadataJson() {
    }

    /**
     * @param where the sentence subject, e.g. {@code "The jar section in the mcmod.info of foo.jar"}
     * @return the value of a key that has to be a JSON string, or {@code null} when the key is absent
     * @throws LoaderException when the key holds anything else: a boolean or a number is not text, and letting one
     *                         through as {@code "true"} or {@code "1"} means the jar silently declares a value it did
     *                         not intend
     */
    @Nullable
    public static String optionalText(@Nullable JsonElement element, String where, String key) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new LoaderException(where + " declares \"" + key + "\" as " + describeType(element)
                    + ". Expected a string, as in \"" + key + "\": \"...\".");
        }
        return element.getAsString();
    }

    /**
     * @return the value of a key where a number is a natural spelling of the same text, or {@code null} when absent
     *
     * <p>Only for values that are text without being prose: a schema version, a library version, a mod version. Booleans
     * and structures are refused in both readers: {@code true} is not a version of anything.
     */
    @Nullable
    public static String optionalScalarText(@Nullable JsonElement element, String where, String key) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (!element.isJsonPrimitive() || element.getAsJsonPrimitive().isBoolean()) {
            throw new LoaderException(where + " declares \"" + key + "\" as " + describeType(element)
                    + ". Expected a string or a number.");
        }
        return element.getAsString();
    }

    /** @return what a JSON value is, for a message that says what was found instead of what was wanted */
    public static String describeType(JsonElement element) {
        if (element.isJsonObject()) {
            return "an object";
        }
        if (element.isJsonArray()) {
            return "an array";
        }
        return element.getAsJsonPrimitive().isBoolean() ? "a boolean" : "a number";
    }
}
