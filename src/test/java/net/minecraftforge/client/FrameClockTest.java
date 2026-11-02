package net.minecraftforge.client;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FrameClockTest {

    @AfterEach
    void resetClock() {
        FrameClock.reset();
    }

    @Test
    void firstFrameUsesSixtyFramesPerSecond() {
        FrameClock.beginFrame(1_000L);
        assertEquals(1.0F / 60.0F, FrameClock.getDeltaSeconds(), 1.0e-6F);
    }

    @Test
    void measuresElapsedTime() {
        FrameClock.beginFrame(1_000L);
        FrameClock.beginFrame(1_000L + 16_666_667L);
        assertEquals(16_666_667L / 1.0e9F, FrameClock.getDeltaSeconds(), 1.0e-6F);
    }

    @Test
    void clampsLongFrames() {
        FrameClock.beginFrame(1_000L);
        FrameClock.beginFrame(1_000_000_000L);
        assertEquals(0.1F, FrameClock.getDeltaSeconds());
    }

    @Test
    void nonPositiveElapsedTimeBecomesZero() {
        FrameClock.beginFrame(1_000L);
        FrameClock.beginFrame(1_000L);
        assertEquals(0.0F, FrameClock.getDeltaSeconds());

        FrameClock.beginFrame(500L);
        assertEquals(0.0F, FrameClock.getDeltaSeconds());
    }
}
