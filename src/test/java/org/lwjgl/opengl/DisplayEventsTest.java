package org.lwjgl.opengl;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DisplayEventsTest {
    @Test
    void windowCallbacksPreserveOrderContextAndCallingThread() {
        List<String> events = new ArrayList<>();
        Thread caller = Thread.currentThread();
        DisplayWindowContext before = new DisplayWindowContext(42, 0L, 0L, null, null, null, "before", false);
        DisplayWindowContext after = new DisplayWindowContext(0, 123L, 0L, null, null, null, "after", false);
        SwapchainInvalidatingChange change = new SwapchainInvalidatingChange(SwapchainInvalidatingChange.Kind.FULLSCREEN, true, null);
        DisplayEvents.addPreWindowCreateListener(context -> {
            assertSame(before, context);
            assertSame(caller, Thread.currentThread());
            events.add("pre");
        });
        DisplayEvents.addPostWindowCreateListener(context -> {
            assertSame(after, context);
            events.add("post");
        });
        DisplayEvents.addPreSwapchainInvalidatingChangeListener(event -> {
            assertSame(change, event);
            assertSame(caller, Thread.currentThread());
            events.add("change");
        });
        DisplayEvents.addPreWindowDestroyListener(context -> {
            assertSame(after, context);
            events.add("destroy");
        });

        try {
            DisplayEvents.setCreateGLContext(false);
            assertFalse(DisplayEvents.isCreateGLContextEnabled());
            DisplayEvents.firePreWindowCreate(before);
            DisplayEvents.firePostWindowCreate(after);
            DisplayEvents.firePreSwapchainInvalidatingChange(change);
            DisplayEvents.firePreWindowDestroy(after);
            assertEquals(List.of("pre", "post", "change", "destroy"), events);
        } finally {
            DisplayEvents.setCreateGLContext(true);
        }
        assertTrue(DisplayEvents.isCreateGLContextEnabled());
    }
}
