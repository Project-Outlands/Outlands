package com.cleanroommc.lwjgl.legacy;

import net.minecraft.launchwrapper.IClassTransformer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Rewrites LWJGL2 call sites found in mod classes to native LWJGL3 calls.
 * Never modifies {@code org.lwjgl.**}: missing classes are provided separately, missing members go through adapters.
 */
public class Lwjgl2CompatTransformer implements IClassTransformer {

    private static final Logger LOGGER = LogManager.getLogger("Cleanroom|lwjgl2compat");

    private static final String[] EXCLUDED_PREFIXES = {
            "org/lwjgl/",
            "com/cleanroommc/lwjgl/",
            "net/minecraftforge/fml/relauncher/",
            "java/",
            "javax/",
            "jdk/",
            "sun/",
            "com/sun/",
            "org/objectweb/asm/",
    };

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (basicClass == null) {
            return null;
        }
        String dotted = transformedName != null ? transformedName : name;
        if (dotted == null || dotted.isEmpty()) {
            return basicClass;
        }
        String internal = dotted.replace('.', '/');
        if (isExcluded(internal)) {
            return basicClass;
        }
        if (!ConstantPoolRefs.mentionsLwjgl(basicClass) || !ConstantPoolRefs.hasLwjgl2MemberRef(basicClass)) {
            return basicClass;
        }
        try {
            byte[] rewritten = CompatRewriter.rewrite(basicClass);
            if (rewritten == null) {
                return basicClass;
            }
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("rewrote LWJGL2 call sites in {}", internal);
            }
            return rewritten;
        } catch (Throwable t) {
            // Keep the original bytes: the remaining shim layer still covers this class.
            LOGGER.error("failed to rewrite LWJGL2 call sites in {}", internal, t);
            return basicClass;
        }
    }

    private static boolean isExcluded(String internal) {
        for (String prefix : EXCLUDED_PREFIXES) {
            if (internal.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
