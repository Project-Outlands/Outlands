package net.minecraftforge.client.event;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.fml.common.eventhandler.Cancelable;

/**
 * Fired once per rendered frame before the local player's view is turned. The yaw and pitch are the values
 * vanilla would pass to {@link EntityPlayer#turn(float, float)}, already scaled by mouse sensitivity and the
 * invert-mouse setting. Handlers may adjust them; cancelling suppresses the turn entirely.
 *
 * <p>Fired on the client render thread. The frame delta is the time since the previous rendered frame, clamped
 * to {@code [0, 0.1]} seconds.
 */
@Cancelable
public class MouseTurnEvent extends PlayerEvent {

    private float yaw;
    private float pitch;
    private final float deltaSeconds;

    public MouseTurnEvent(EntityPlayer player, float yaw, float pitch, float deltaSeconds) {
        super(player);
        this.yaw = yaw;
        this.pitch = pitch;
        this.deltaSeconds = deltaSeconds;
    }

    public float getYaw() {
        return yaw;
    }

    public void setYaw(float yaw) {
        this.yaw = yaw;
    }

    public float getPitch() {
        return pitch;
    }

    public void setPitch(float pitch) {
        this.pitch = pitch;
    }

    public float getDeltaSeconds() {
        return deltaSeconds;
    }
}
