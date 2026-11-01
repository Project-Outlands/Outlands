package net.minecraftforge.common.config;

import java.io.File;

/**
 * Turns the text of one config file into the {@link Configuration} model and back.
 *
 * <p>The model is shared by every format: a {@link ConfigCategory} tree of {@link Property} values. A codec only moves
 * syntax in and out of it, which is what keeps a key meaning the same thing in every format. The rules that decide
 * that - what a nested table is, what a scalar is, what is refused - live in {@link ConfigTree} so they cannot drift
 * between codecs.
 */
public interface IConfigCodec {

    /** Serialises {@code cfg}. Called with the model, never with a file, so every format shares the atomic write. */
    String write(Configuration cfg);

    /**
     * Whether an existing file may be replaced.
     *
     * <p>HOCON may not: it can {@code include} other files and read the environment, and dumping the parsed result would
     * bake those substitutions in, so a later change to the include would stop having any effect. Such a format is
     * written once when it is missing and then left alone - the model keeps the keys the file does not mention.
     *
     * @return {@code false} for formats that must never be rewritten
     */
    default boolean rewriteable() {
        return true;
    }

    /**
     * Fills {@code cfg} from {@code file}. Implementations add categories and properties; they never clear what is
     * already there, because a file that is missing a key must not remove it.
     */
    void read(Configuration cfg, File file);
}
