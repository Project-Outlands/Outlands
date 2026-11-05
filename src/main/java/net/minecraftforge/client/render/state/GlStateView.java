package net.minecraftforge.client.render.state;

import org.jspecify.annotations.Nullable;

/**
 * Read-only view of the GL state that vanilla's {@code GlStateManager} caches.
 *
 * <p>This exists so that a renderer can ask what the current state is instead of mixing an
 * {@code @Accessor} into {@code GlStateManager} to reach its private fields.</p>
 *
 * <p><b>Threading.</b> Render thread only. A caller on another thread gets an implementation-defined
 * answer (either a stale snapshot or an exception); do not rely on either.</p>
 *
 * <p><b>No side effects.</b> Nothing in this interface may issue a GL call or change state. It reports what
 * the state <i>is</i>, as last committed by the render path.</p>
 */
public interface GlStateView {

    /** The default instance, used when no engine has installed a richer implementation. */
    GlStateView UNAVAILABLE = new GlStateView() {
        @Override
        public boolean isEnabled(int cap) {
            throw unavailable();
        }

        @Override
        public int textureName(int unit) {
            throw unavailable();
        }

        @Override
        public int activeTextureUnit() {
            throw unavailable();
        }

        @Override
        public Fog fog() {
            throw unavailable();
        }

        private UnsupportedOperationException unavailable() {
            return new UnsupportedOperationException(
                    "GL state is not readable on this backend; register a GlStateView first");
        }
    };

    /**
     * Whether a capability is currently enabled, mirroring {@code GlStateManager} booleans.
     *
     * @param cap a GL capability constant, e.g. {@code GL_BLEND}
     */
    boolean isEnabled(int cap);

    /**
     * The texture bound to a unit, as vanilla tracks it.
     *
     * @param unit the raw GL texture unit constant, e.g. {@code GL_TEXTURE0}
     * @return the texture name, or {@code 0} when nothing is bound
     */
    int textureName(int unit);

    /** The active texture unit, as a raw GL constant. */
    int activeTextureUnit();

    /** The cached fog state. */
    Fog fog();

    /**
     * Fog parameters, mirroring {@code GlStateManager.FogState}.
     *
     * @param mode    the fog mode constant, e.g. {@code GL_EXP} / {@code GL_LINEAR}
     * @param density fog density
     * @param start   linear fog start distance
     * @param end     linear fog end distance
     */
    record Fog(int mode, float density, float start, float end) {}

    /** Returns whether a view has been installed. */
    static boolean isAvailable(@Nullable GlStateView view) {
        return view != null && view != UNAVAILABLE;
    }
}
