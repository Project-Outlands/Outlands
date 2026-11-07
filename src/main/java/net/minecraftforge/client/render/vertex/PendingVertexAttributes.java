package net.minecraftforge.client.render.vertex;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The generic vertex attributes a draw declared, on a backend that has no client-side attribute arrays.
 *
 * <p><b>Why recording is the whole job.</b> The GL path enables attribute arrays and points them at a client buffer,
 * then {@code glDrawArrays} reads them. A pipeline backend reads an interleaved vertex buffer described by the
 * pipeline instead, so there is nothing to enable or point at - but the declarations are not worthless: they are the
 * only statement of which generic attributes a draw wanted, and a vertex format that carries one is converted by
 * {@link VertexConverter} accordingly.</p>
 *
 * <p><b>Keyed by attribute index, last write wins.</b> Vanilla and Forge set one attribute at a time between
 * {@code preDraw} and {@code postDraw} for the same draw, so a map is the shape of what actually happens rather than
 * a list of what could.</p>
 */
public final class PendingVertexAttributes {

    /** One declared attribute: its component count, type and stride, and where the bytes are. */
    public record Attribute(int index, int count, int glType, int stride) {}

    private static final Map<Integer, Attribute> CURRENT = new LinkedHashMap<>();

    private PendingVertexAttributes() {}

    /** Records a declaration, replacing any previous one for the same index. */
    public static void set(int index, int count, int glType, int stride) {
        synchronized (CURRENT) {
            CURRENT.put(index, new Attribute(index, count, glType, stride));
        }
    }

    /** Forgets every declaration, which is what {@code glDisableVertexAttribArray} amounts to here. */
    public static void clear() {
        synchronized (CURRENT) {
            CURRENT.clear();
        }
    }

    /** How many attributes are declared. Diagnostics. */
    public static int size() {
        synchronized (CURRENT) {
            return CURRENT.size();
        }
    }
}
