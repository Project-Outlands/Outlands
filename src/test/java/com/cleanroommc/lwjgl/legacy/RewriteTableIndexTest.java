package com.cleanroommc.lwjgl.legacy;

import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rewrite table and the LWJGL2 class inventory are produced by the same generator, but nothing used to make them
 * agree. These assertions hold the two ends together: the gate is derived from the table, and every owner it derived
 * has to be a name the inventory knows.
 */
public class RewriteTableIndexTest {

    @Test
    public void ownersAreDerivedFromEveryTable() {
        Set<String> owners = RewriteTableIndex.OWNERS;
        assertFalse(owners.isEmpty(), "no owner derived: the gate would reject every class");
        assertTrue(owners.contains("org/lwjgl/opengl/GL11"), "GL11 renames must reach the gate");
        assertTrue(owners.contains("org/lwjgl/openal/AL10"), "AL10 renames must reach the gate");
        assertTrue(owners.contains("org/lwjgl/openal/AL"), "AL context adapters must reach the gate");
    }

    @Test
    public void everyRewrittenOwnerIsALwjgl2Class() {
        for (String owner : RewriteTableIndex.OWNERS) {
            assertTrue(Lwjgl2ClassSet.NAMES.contains(owner),
                    "the table rewrites " + owner + ", which the LWJGL2 class inventory does not know");
        }
    }

    @Test
    public void membershipIsMatchedOnOwnerNameAndDescriptor() {
        // the gate must accept exactly the members the rewriter acts on, not every member of a touched class
        assertTrue(RewriteTableIndex.isRewritable("org/lwjgl/opengl/GL11", "glFog", "(ILjava/nio/FloatBuffer;)V"));
        assertFalse(RewriteTableIndex.isRewritable("org/lwjgl/opengl/GL11", "glFog", "(IF)V"),
                "the scalar overload is untouched and must not open the gate");
        assertFalse(RewriteTableIndex.isRewritable("org/lwjgl/BufferUtils", "createFloatBuffer", "(I)Ljava/nio/FloatBuffer;"),
                "a class the shim provides verbatim is not the table's business");
    }

    @Test
    public void keyParsingTakesTheOwnerPart() {
        String key = RewriteTable.key("org/lwjgl/opengl/GL11", "glFog", "(ILjava/nio/FloatBuffer;)V");
        assertTrue(RewriteTableIndex.ownerOf(key).equals("org/lwjgl/opengl/GL11"));
    }
}
