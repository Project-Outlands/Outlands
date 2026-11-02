package net.minecraftforge.common.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EntityRotationTest {

    @Test
    void wrapsIntoHalfOpenRange() {
        assertEquals(-180.0F, EntityRotation.wrapDegrees(180.0F));
        assertEquals(-180.0F, EntityRotation.wrapDegrees(-180.0F));
        assertEquals(-180.0F, EntityRotation.wrapDegrees(540.0F));
        assertEquals(10.0F, EntityRotation.wrapDegrees(370.0F));
    }

    @Test
    void interpolatesBetweenTicks() {
        assertEquals(5.0F, EntityRotation.interpolate(0.0F, 10.0F, 0.5F));
        assertEquals(10.0F, EntityRotation.interpolate(0.0F, 10.0F, 1.0F));
    }

    @Test
    void leavesSmallStepsAlone() {
        assertEquals(170.0F, EntityRotation.adjustPrevAcrossSeam(170.0F, 170.0F, 179.0F));
        assertEquals(10.0F, EntityRotation.adjustPrevAcrossSeam(10.0F, 0.0F, 180.0F));
        assertEquals(10.0F, EntityRotation.adjustPrevAcrossSeam(10.0F, 0.0F, -180.0F));
    }

    @Test
    void shiftsAcrossPositiveSeam() {
        assertEquals(190.0F, EntityRotation.adjustPrevAcrossSeam(-170.0F, -170.0F, 170.0F));
        assertEquals(550.0F, EntityRotation.adjustPrevAcrossSeam(190.0F, 190.0F, 170.0F + 360.0F));
    }

    @Test
    void shiftsAcrossNegativeSeam() {
        assertEquals(-190.0F, EntityRotation.adjustPrevAcrossSeam(170.0F, 170.0F, -170.0F));
    }

    @Test
    void handlesUnwrappedPreviousValues() {
        assertEquals(350.0F, EntityRotation.adjustPrevAcrossSeam(350.0F, 350.0F, 370.0F));
        assertEquals(10.0F, EntityRotation.adjustPrevAcrossSeam(10.0F, 10.0F, 20.0F));
    }
}
