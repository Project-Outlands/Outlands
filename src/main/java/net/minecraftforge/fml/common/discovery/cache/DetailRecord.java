package net.minecraftforge.fml.common.discovery.cache;

import java.util.Map;

import net.minecraftforge.fml.common.asm.transformers.AtRule;

/**
 * The detail of one archive: what every scanned class says beyond its header and member list, plus the access
 * transformer rules this archive declares.
 *
 * <p>Produced by the same visit that reads the class headers, stored in its own file ({@link DetailFile}) so that it can
 * be absent, and rebuilt from the jar alone when a reader asks for it and it is missing.
 */
public record DetailRecord(JarFingerprint fingerprint, Map<String, ClassDetail> classes, AtRule[] atRules) {

    public static DetailRecord empty(JarFingerprint fingerprint) {
        return new DetailRecord(fingerprint, Map.of(), new AtRule[0]);
    }

    /** @return whether the archive has nothing to say, so no file is written */
    public boolean isEmpty() {
        if (atRules.length > 0) {
            return false;
        }
        for (ClassDetail detail : classes.values()) {
            if (!detail.isEmpty()) {
                return false;
            }
        }
        return true;
    }
}
