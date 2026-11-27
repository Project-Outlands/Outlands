package net.minecraftforge.fml.common.discovery.asm;


import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import net.minecraftforge.fml.common.asm.transformers.AtRules;
import net.minecraftforge.fml.common.discovery.cache.ClassDetail;
import net.minecraftforge.fml.common.discovery.cache.MemberDetail;
import net.minecraftforge.fml.common.discovery.cache.MemberRecord;

import com.google.common.io.CharSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The visitor keeps what only a language tool asks for: generic signatures, the class' own inner-class entry, the
 * enclosing method, the source file, the member signatures, exceptions and constant values.
 */
class ClassDetailTest {

    private static byte[] sampleClass() {
        final ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "pkg/Outer$Inner", null, "java/lang/Object",
                new String[] {"java/lang/Comparable"});
        writer.visitSource("Outer.java", null);
        writer.visitOuterClass("pkg/Outer", "make", "()Lpkg/Outer$Inner;");
        writer.visitInnerClass("pkg/Outer$Inner", "pkg/Outer", "Inner", Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC);

        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "MAX", "I", null, 7)
                .visitEnd();
        writer.visitField(Opcodes.ACC_PRIVATE, "items", "Ljava/util/List;", "Ljava/util/List<Ljava/lang/String;>;", null)
                .visitEnd();

        final MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, "add", "(Ljava/util/List;)V",
                "(Ljava/util/List<Ljava/lang/String;>;)V", new String[] {"java/io/IOException"});
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    @Test
    void theVisitorKeepsWhatALanguageServerAsksFor() throws Exception {
        final ClassDetail detail = new ASMModParser(sampleClass()).toDetail();

        assertEquals("Inner", detail.inner().innerName());
        assertEquals("pkg/Outer", detail.inner().outer());
        assertEquals(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, detail.inner().access(),
                "static and private only exist in the inner-class entry, not in the class' own flags");
        assertEquals("pkg/Outer", detail.enclosing().owner());
        assertEquals("make", detail.enclosing().name());
        assertEquals("()Lpkg/Outer$Inner;", detail.enclosing().descriptor());
        assertEquals("Outer.java", detail.sourceFile());

        final MemberDetail constant = detail.members().get(MemberRecord.key("MAX", "I", false));
        assertNotNull(constant, "a static final field's value is what a hover shows");
        assertEquals("7", constant.constantValue());

        final MemberDetail generic = detail.members().get(
                MemberRecord.key("items", "Ljava/util/List;", false));
        assertNotNull(generic);
        assertEquals("Ljava/util/List<Ljava/lang/String;>;", generic.signature());

        final MemberDetail method = detail.members().get(
                MemberRecord.key("add", "(Ljava/util/List;)V", true));
        assertNotNull(method);
        assertEquals("(Ljava/util/List<Ljava/lang/String;>;)V", method.signature());
        assertEquals("java/io/IOException", method.exceptions()[0]);
        assertNull(detail.signature(), "this class declares no type signature");
    }

    @Test
    void aClassWithoutDetailHasNone() throws Exception {
        final ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "pkg/Plain", null, "java/lang/Object", null);
        writer.visitEnd();
        assertEquals(ClassDetail.EMPTY, new ASMModParser(writer.toByteArray()).toDetail());
    }

    @Test
    void theAccessTransformerGrammarIsTheLoaders() throws Exception {
        final var rules = AtRules.parse(CharSource.wrap(String.join("\n",
                "# a comment",
                "public net/minecraft/world/World",
                "public-f net/minecraft/world/World field",
                "protected net/minecraft/world/World method()V",
                "public net/minecraft/item/ItemStack *")));
        assertEquals(4, rules.size());
        assertEquals("net/minecraft/world/World", rules.get(0).owner());
        assertEquals("", rules.get(0).name());
        assertEquals(true, rules.get(0).classVisibility());
        assertEquals("field", rules.get(1).name());
        assertEquals(true, rules.get(1).changeFinal());
        assertEquals(false, rules.get(1).markFinal());
        assertEquals("method", rules.get(2).name());
        assertEquals("()V", rules.get(2).descriptor());
        assertEquals("*", rules.get(3).name());
        assertEquals("", rules.get(3).descriptor());
    }
}
