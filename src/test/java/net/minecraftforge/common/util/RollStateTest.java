package net.minecraftforge.common.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RollStateTest {

    @Test
    void setWrapsAndInterpolatesFromPreviousTick() {
        RollState state = new RollState();
        state.setRoll(10.0F);
        state.tick(1);

        assertEquals(10.0F, state.getRoll());
        assertEquals(10.0F, state.getRoll(0.5F));

        state.setRoll(350.0F);
        assertEquals(-10.0F, state.getRoll());
        assertEquals(0.0F, state.getRoll(0.5F));
    }

    @Test
    void firstTickSyncAppliesImmediatelyAndSnaps() {
        RollState state = new RollState();
        state.onSynced(30.0F, 0);

        assertEquals(30.0F, state.getRoll());
        assertEquals(30.0F, state.getRoll(0.5F));
    }

    @Test
    void laterSyncWaitsForTheNextTick() {
        RollState state = new RollState();
        state.onSynced(30.0F, 2);

        assertEquals(0.0F, state.getRoll());
        state.tick(2);
        assertEquals(30.0F, state.getRoll());
        assertEquals(15.0F, state.getRoll(0.5F));
    }

    @Test
    void syncOnFirstRealTickSnaps() {
        RollState state = new RollState();
        state.onSynced(30.0F, 1);
        state.tick(1);

        assertEquals(30.0F, state.getRoll());
        assertEquals(30.0F, state.getRoll(0.5F));
    }

    @Test
    void keepsOnlyTheLastPendingSync() {
        RollState state = new RollState();
        state.onSynced(10.0F, 2);
        state.onSynced(20.0F, 2);
        state.tick(2);

        assertEquals(20.0F, state.getRoll());
    }

    @Test
    void snapMovesTheInterpolationAnchor() {
        RollState state = new RollState();
        state.setRoll(10.0F);
        state.tick(1);
        state.setRoll(20.0F);
        assertEquals(15.0F, state.getRoll(0.5F));

        state.snap();
        assertEquals(20.0F, state.getRoll(0.5F));
    }
}
