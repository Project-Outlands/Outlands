package net.minecraftforge.fml.common.discovery;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

import javax.annotation.Nullable;

import net.minecraftforge.fml.common.FMLLog;
import net.minecraftforge.fml.common.MetadataCollection;
import net.minecraftforge.fml.common.asm.transformers.AtRule;
import net.minecraftforge.fml.common.asm.transformers.AtRules;

import com.google.common.io.CharSource;

/**
 * The access transformer rules one jar declares, parsed and attached to that jar.
 *
 * <p>Which entries are a jar's access transformers is the loader's own question and is answered by
 * {@link JarDeclaration#accessTransformersOf}, from the manifest attribute or the jar declaration; the grammar is
 * {@link AtRules}, the same one the transformer runs. The rules stay with the declaring archive so that its detail file
 * is a function of that jar alone; a reader unions every archive's before applying them, which is also what
 * {@link net.minecraftforge.fml.common.asm.transformers.ModAccessTransformer} ends up doing.
 */
public final class JarAtRules {

    private static final AtRule[] NONE = new AtRule[0];

    private JarAtRules() {}

    /** @return the rules this jar declares, in file order; empty when it declares none or they cannot be read */
    public static AtRule[] of(JarFile jar, @Nullable MetadataCollection metadata) {
        final Attributes attributes;
        try {
            final Manifest manifest = jar.getManifest();
            attributes = manifest == null ? null : manifest.getMainAttributes();
        } catch (Exception e) {
            return NONE;
        }
        final JarDeclaration declaration = metadata == null ? null : metadata.getJarDeclaration();
        final List<String> entries = JarDeclaration.accessTransformersOf(attributes, declaration);
        if (entries.isEmpty()) {
            return NONE;
        }
        final List<AtRule> rules = new ArrayList<>();
        for (String entry : entries) {
            final JarEntry found = jar.getJarEntry("META-INF/" + entry);
            if (found == null) {
                continue;
            }
            try (InputStream in = jar.getInputStream(found)) {
                rules.addAll(AtRules.parse(CharSource.wrap(new String(in.readAllBytes(), StandardCharsets.UTF_8))));
            } catch (Exception e) {
                FMLLog.log.debug("Could not read the access transformer {} of {}", entry, jar.getName(), e);
            }
        }
        return rules.isEmpty() ? NONE : rules.toArray(NONE);
    }
}
