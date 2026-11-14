package net.minecraftforge.client.render;

import net.minecraftforge.client.render.shader.GlShaders;
import net.minecraftforge.client.render.shim.FrameShim;
import net.minecraftforge.client.render.shim.ShimGl;

/**
 * Points the shim facade at a non-GL backend for a test, and puts it back afterwards.
 *
 * <p>Both halves matter. Without the selection the facade passes through to {@code GL11}, which in a unit test
 * means a {@code NullPointerException} from inside LWJGL rather than an assertion; without the restore the frozen
 * selection and the installed facade leak into every later test in the same JVM, and the next class to ask
 * {@code FrameStates.forNonGl()} gets a frame state it did not install.</p>
 */
public final class NonGlTestBackend {

    private NonGlTestBackend() {}

    public static void select() {
        RenderBackends.resetForTests();
        RenderBackendSelection.resetForTests();
        GlShaders.reset();

        RenderBackend backend = new RenderBackend() {
            @Override
            public String id() {
                return "test-non-gl";
            }

            @Override
            public long sdlWindowFlags() {
                return 0L;
            }
        };
        RenderBackends.register(backend);
        RenderBackendSelection.register(new RenderBackendSelection.Candidate(backend, () -> true, 10));
        RenderBackendSelection.select();
        // The frame state is a singleton: without this, a test that pushes an attribute block or leaves a matrix
        // pushed hands that state to the next test, which then fails somewhere else.
        net.minecraftforge.client.render.state.backend.FrameStates.resetForTests();
        ShimGl.setImpl(new FrameShim());
    }

    public static void restore() {
        ShimGl.setImpl(ShimGl.REAL_GL);
        GlShaders.reset();
        RenderBackends.resetForTests();
        RenderBackendSelection.resetForTests();
    }
}
