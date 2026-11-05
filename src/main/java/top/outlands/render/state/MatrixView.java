package top.outlands.render.state;

import org.jspecify.annotations.Nullable;

import java.nio.FloatBuffer;

/**
 * Read-only view of the transform matrices and viewport currently in effect.
 *
 * <p>Replaces the {@code @Accessor}s that reach {@code ActiveRenderInfo.PROJECTION} / {@code MODELVIEW} /
 * {@code OBJECTCOORDS} / {@code VIEWPORT} and the mixin that rebuilds a JOML frustum after
 * {@code ClippingHelperImpl#init}.</p>
 *
 * <p><b>Threading.</b> Render thread only.</p>
 */
public interface MatrixView {

    /** See {@link GlStateView#UNAVAILABLE} for the contract. */
    MatrixView UNAVAILABLE = new MatrixView() {
        @Override
        public FloatBuffer projection() {
            throw unavailable();
        }

        @Override
        public FloatBuffer modelView() {
            throw unavailable();
        }

        @Override
        public float[] viewProjection() {
            throw unavailable();
        }

        @Override
        public float[] frustumPlanes() {
            throw unavailable();
        }

        @Override
        public float[] objectCoords() {
            throw unavailable();
        }

        @Override
        public int[] viewport() {
            throw unavailable();
        }

        private UnsupportedOperationException unavailable() {
            return new UnsupportedOperationException(
                    "matrices are not readable on this backend; register a MatrixView first");
        }
    };

    /** The projection matrix, as uploaded to GL. */
    FloatBuffer projection();

    /** The model-view matrix, as uploaded to GL. */
    FloatBuffer modelView();

    /**
     * The view-projection matrix used for culling and raster occlusion.
     *
     * <p><b>Contract:</b> this matrix carries rotation and projection but <b>no camera translation</b>.
     * The viewport transform supplies the camera position and the consumer applies the camera-relative
     * offset per section. Stripping the view-space translation (eye height, third-person offset, bobbing)
     * is part of the contract, not an implementation detail — a consumer that adds its own translation on
     * top would double-count it.</p>
     *
     * <p>Row-major 4x4, 16 elements. The returned array may be reused between calls; copy it if you need to
     * keep it.</p>
     */
    float[] viewProjection();

    /**
     * The six frustum planes, in the order JOML's {@code FrustumIntersection} expects.
     *
     * <p>Six planes of four components each, 24 elements, laid out as {@code nx, ny, nz, d} per plane.
     * Same reuse caveat as {@link #viewProjection()}.</p>
     */
    float[] frustumPlanes();

    /** The object-space origin under the current transform, as {@code x, y, z, w}. */
    float[] objectCoords();

    /** The viewport as {@code x, y, width, height}. */
    int[] viewport();

    static boolean isAvailable(@Nullable MatrixView view) {
        return view != null && view != UNAVAILABLE;
    }
}
