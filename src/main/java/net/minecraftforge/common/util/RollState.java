package net.minecraftforge.common.util;

/**
 * Roll state for one entity. The value is in degrees and is advanced on the entity's own thread; it is not
 * thread-safe. {@code prevRoll} may lie outside {@code [-180, 180)} so interpolation across the seam takes the
 * short way round.
 */
public final class RollState {

    private float roll;
    private float prevRoll;
    private boolean pending;
    private float pendingRoll;

    public float getRoll() {
        return roll;
    }

    public float getRoll(float partialTicks) {
        return EntityRotation.interpolate(prevRoll, roll, partialTicks);
    }

    /** Sets the current roll, wrapping it and adjusting the interpolation anchor across the seam. */
    public void setRoll(float value) {
        float last = roll;
        roll = EntityRotation.wrapDegrees(value);
        prevRoll = EntityRotation.adjustPrevAcrossSeam(prevRoll, last, roll);
    }

    /**
     * Accepts a value received from the server. Before the entity's first tick the value is applied immediately
     * and the interpolation anchor is snapped, so a newly tracked entity never renders at zero. Afterwards the
     * value is held until the next {@link #tick(int)} to keep interpolation tick-aligned.
     */
    public void onSynced(float value, int ticksExisted) {
        if (ticksExisted == 0) {
            pending = false;
            setRoll(value);
            prevRoll = roll;
        } else {
            pending = true;
            pendingRoll = value;
        }
    }

    /** Advances one real tick: snapshots the interpolation anchor and applies a pending synced value. */
    public void tick(int ticksExisted) {
        prevRoll = roll;
        if (pending) {
            pending = false;
            setRoll(pendingRoll);
            if (ticksExisted <= 1) {
                prevRoll = roll;
            }
        }
    }

    /** Snaps the interpolation anchor to the current roll, e.g. after a teleport or position correction. */
    public void snap() {
        prevRoll = roll;
    }
}
