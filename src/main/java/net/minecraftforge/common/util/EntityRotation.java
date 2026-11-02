package net.minecraftforge.common.util;

import net.minecraft.util.math.MathHelper;

/**
 * Degree helpers for the entity roll axis. The values are in degrees and are not normalized unless stated;
 * {@link #adjustPrevAcrossSeam(float, float, float)} accepts unwrapped previous values so per-frame writers can
 * advance {@code prevRoll} and {@code roll} by the same delta without producing a long interpolation path.
 */
public final class EntityRotation {

    private EntityRotation() {
    }

    /** Wraps an angle to {@code [-180, 180)}. */
    public static float wrapDegrees(float degrees) {
        return MathHelper.wrapDegrees(degrees);
    }

    /** Interpolates between the previous and current angle. */
    public static float interpolate(float previous, float current, float partialTicks) {
        return partialTicks == 1.0F ? current : previous + (current - previous) * partialTicks;
    }

    /**
     * Keeps interpolation continuous across the +/-180 seam. When the step from {@code last} to {@code next} is
     * larger than 180 degrees, {@code previous} is moved by whole turns until the step is within +/-180; a step of
     * exactly +/-180 is left alone.
     *
     * @param previous the previous-tick value used for interpolation, possibly unwrapped
     * @param last the value before the current update
     * @param next the value after the current update
     * @return the previous-tick value adjusted for the seam
     */
    public static float adjustPrevAcrossSeam(float previous, float last, float next) {
        double delta = (double) next - last;
        if (delta > 180.0D) {
            return (float) (previous + Math.ceil((delta - 180.0D) / 360.0D) * 360.0D);
        }
        if (delta < -180.0D) {
            return (float) (previous + Math.floor((delta + 180.0D) / 360.0D) * 360.0D);
        }
        return previous;
    }
}
