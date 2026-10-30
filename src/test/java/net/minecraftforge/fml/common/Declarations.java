package net.minecraftforge.fml.common;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import net.minecraftforge.fml.common.discovery.JarDeclaration;

/**
 * Builds a {@link JarDeclaration} from the JSON a jar would carry, so that tests that are about something else do not
 * have to spell out the mandatory fields of the {@code jar} section.
 */
final class Declarations {

    private Declarations() {
    }

    /** @param jarSectionBody additional keys, each starting with a comma */
    static JarDeclaration jar(String jarSectionBody) {
        String json = "{\"jar\":{\"schemaVersion\":1,\"license\":\"MIT\"" + jarSectionBody + "}}";
        JarDeclaration declaration = MetadataCollection
                .from(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)), "test.jar")
                .getJarDeclaration();
        if (declaration == null) {
            throw new IllegalStateException("test json produced no jar declaration: " + json);
        }
        return declaration;
    }

    /** A declaration providing one language, without stating which versions it supports. */
    static JarDeclaration providing(String language, String className) {
        return jar(",\"providers\":{\"" + language + "\":\"" + className + "\"}");
    }
}
