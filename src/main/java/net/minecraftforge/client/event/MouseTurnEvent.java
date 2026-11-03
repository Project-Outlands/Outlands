package net.minecraftforge.client.event;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.fml.common.eventhandler.Cancelable;

/**
 * Fired once per rendered frame before the local player's view is turned. Both values are per-frame deltas, not
 * absolute angles: {@code yawDelta} is the yaw argument vanilla passes to {@link EntityPlayer#turn(float, float)}
 * and becomes {@code 0.15 * yawDelta} added to the player's yaw; {@code pitchDelta} is the pitch argument and
 * becomes {@code 0.15 * pitchDelta} subtracted from the player's pitch. The values are already scaled by mouse
 * sensitivity and by the invert-mouse setting. Handlers may adjust either delta; cancelling suppresses the turn
 * entirely.
 *
 * <p>Fired on the client render thread. {@link #getDeltaSeconds()} is the duration of the frame that just ended,
 * meant for smoothing the current frame; it is clamped to {@code [0, 0.1]} seconds.
 */
@Cancelable
public class MouseTurnEvent extends PlayerEvent {

    private float yawDelta;
    private float pitchDelta;
    private final float deltaSeconds;

    public MouseTurnEvent(EntityPlayer player, float yawDelta, float pitchDelta, float deltaSeconds) {
        super(player);
        this.yawDelta = yawDelta;
        this.pitchDelta = pitchDelta;
        this.deltaSeconds = deltaSeconds;
    }

    public float getYawDelta() {
        return yawDelta;
    }

    public void setYawDelta(float yawDelta) {
        this.yawDelta = yawDelta;
    }

    public float getPitchDelta() {
        return pitchDelta;
    }

    public void setPitchDelta(float pitchDelta) {
        this.pitchDelta = pitchDelta;
    }

    public float getDeltaSeconds() {
        return deltaSeconds;
    }
}
