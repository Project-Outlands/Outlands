package net.minecraftforge.fml.common.discovery.cache;

import java.util.Map;

import javax.annotation.Nullable;

import net.minecraftforge.fml.common.discovery.ASMDataTable;
import net.minecraftforge.fml.common.discovery.ModCandidate;

/**
 * What one class file contributes to the index: its header (name, version, flags, super type, interfaces) and its
 * annotations. Every scanned class has one, not only the ones with annotations, so that the index can answer
 * questions about plain classes too; {@link #hasContent()} says whether the class also contributes to the
 * {@link ASMDataTable} and can be the entry point of a container.
 */
public record ClassScanRecord(
        String internalName,
        int classVersion,
        @Nullable String superName,
        int access,
        String[] interfaces,
        Annotation[] annotations) {

    public record Annotation(
            String annotationName,
            @Nullable String member,
            @Nullable Map<String, Object> values) {}

    public String className() {
        return internalName.replace('/', '.');
    }

    /** Whether the class has something to say to the annotation table or can start a container. */
    public boolean hasContent() {
        return annotations.length > 0 || interfaces.length > 0;
    }

    public void sendToTable(ModCandidate candidate, ASMDataTable table) {
        String className = className();
        for (Annotation annotation : annotations) {
            table.addASMData(
                    candidate, annotation.annotationName(), className, annotation.member(), annotation.values());
        }
        for (String intf : interfaces) {
            table.addASMData(candidate, intf, internalName, null, null);
        }
    }
}
