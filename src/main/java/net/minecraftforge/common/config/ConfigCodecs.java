package net.minecraftforge.common.config;

import javax.annotation.Nullable;

/**
 * Maps a declared {@link Config.Format} to the codec that reads and writes it.
 *
 * <p>The formats exist as an enum with their extensions on it, so this is the only place that has to know a codec per
 * format. A format without an entry here is one nobody can parse yet, and asking for it fails loudly in
 * {@code ConfigManager} rather than silently producing a CFG file under another extension.
 */
final class ConfigCodecs {

    private static final TomlCodec TOML = new TomlCodec();
    private static final YamlCodec YAML = new YamlCodec();
    private static final HoconCodec HOCON = new HoconCodec();
    private static final JsonCodec JSON = new JsonCodec();

    private ConfigCodecs() {}

    /**
     * @return the codec for {@code format}, or {@code null} for {@link Config.Format#CFG}, which is handled by
     *         {@link Configuration} itself because that format is not a document: it is a line-oriented format with
     *         categories, list markers and type prefixes, and its parser predates this interface
     */
    @Nullable
    static IConfigCodec forFormat(Config.Format format) {
        switch (format) {
            case TOML:
                return TOML;
            case YAML:
                return YAML;
            case HOCON:
                return HOCON;
            case JSON:
                return JSON;
            default:
                return null;
        }
    }
}
