package net.minecraftforge.client.render.culling;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SectionFacesTest {

    /**
     * The mask has to agree with vanilla's two predicates for every combination of the three inputs, because the walk
     * it replaces had them as separate {@code &&} terms and any difference here is a section drawn or not drawn.
     */
    @Test
    void theMaskAgreesWithVanillasTwoGatesEverywhere() {
        for (int setFacing = 0; setFacing < 64; setFacing++) {
            for (int enteredFrom = -1; enteredFrom < 6; enteredFrom++) {
                for (int outOfEntered = 0; outOfEntered < 64; outOfEntered++) {
                    int expected = 0;
                    for (int face = 0; face < 6; face++) {
                        boolean alreadyCameFromThere = (setFacing & (1 << (face ^ 1))) != 0;
                        boolean entryFaceSeesIt = enteredFrom < 0 || (outOfEntered & (1 << face)) != 0;
                        if (!alreadyCameFromThere && entryFaceSeesIt) {
                            expected |= 1 << face;
                        }
                    }
                    assertEquals(expected, SectionFaces.expandable(setFacing, enteredFrom, outOfEntered),
                            "setFacing " + setFacing + ", entered from " + enteredFrom
                                    + ", reachable " + outOfEntered);
                }
            }
        }
    }

    @Test
    void oppositeIsItsOwnInverseAndKeepsEveryFace() {
        assertEquals(SectionFaces.ALL, SectionFaces.opposite(SectionFaces.ALL), "every face stays every face");
        for (int mask = 0; mask < 64; mask++) {
            assertEquals(mask, SectionFaces.opposite(SectionFaces.opposite(mask)), "opposite twice is identity");
            assertEquals(Integer.bitCount(mask), Integer.bitCount(SectionFaces.opposite(mask)), "no face is gained");
        }
    }

    @Test
    void aRootExpandsEverywhereAndAnEnclosedOneNowhere() {
        // A root has no entry face, so nothing but the reached-through gate applies: vanilla's `enumfacing2 == null`
        // term is what this is.
        assertEquals(SectionFaces.ALL, SectionFaces.expandable(0, -1, 0), "a root sees every face");
        // The second gate is the section's own answer, and it can be nothing: a section whose air is sealed cannot be
        // expanded through at all, which is how a wall of stone stops the walk.
        assertEquals(0, SectionFaces.expandable(0, 2, 0), "a sealed section expands nowhere");
    }

    @Test
    void leavingBackTheWayItCameIsRefusedByTheReachedThroughGate() {
        // A section reached by stepping NORTH from its parent has facing = NORTH and carries NORTH in setFacing, so
        // it entered this one through SOUTH. Expanding SOUTH would go straight back, and gate 1 refuses it.
        int north = 2;
        int setFacing = 1 << north;
        int backOut = 1 << (north ^ 1);
        int allowed = SectionFaces.expandable(setFacing, north ^ 1, SectionFaces.ALL);
        assertEquals(0, allowed & backOut, "the way it came in is not left again");
        assertEquals(SectionFaces.ALL & ~backOut, allowed, "and every other face is");
        // Reached through WEST as well, both are refused; the gate accumulates rather than replacing.
        int both = setFacing | (1 << 4);
        assertEquals(0, SectionFaces.expandable(both, north ^ 1, SectionFaces.ALL) & SectionFaces.opposite(both),
                "both ways in are refused once both have been used");
    }
}
