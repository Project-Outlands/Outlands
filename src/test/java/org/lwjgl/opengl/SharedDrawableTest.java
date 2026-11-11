package org.lwjgl.opengl;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

class SharedDrawableTest {
    @Test
    void constructorUsesTheSharedTokenAndRetainsTheSource() throws Exception {
        SourceDrawable source = new SourceDrawable();
        SharedDrawable shared = new SharedDrawable(source);
        assertSame(source, shared.getSharingDrawable());
        assertSame(source.tokens.getFirst(), shared.getContext());
        assertNotSame(source.getContext(), shared.getContext());

        ContextGL next = shared.createSharedContext();
        assertEquals(2, source.tokens.size());
        assertSame(source.tokens.get(1), next);
        assertNotSame(shared.getContext(), next);
    }

    private static final class SourceDrawable extends DrawableGL {
        private final List<ContextGL> tokens = new ArrayList<>();

        private SourceDrawable() { super(new ContextGL(0L, 0L, true)); }

        @Override public ContextGL createSharedContext() {
            ContextGL token = new ContextGL(0L, 0L, true);
            tokens.add(token);
            return token;
        }
    }
}
