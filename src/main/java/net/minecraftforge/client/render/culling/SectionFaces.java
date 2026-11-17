package net.minecraftforge.client.render.culling;

/**
 * The face arithmetic of the terrain walk: which of a section's six faces may be expanded into.
 *
 * <p><b>Why this is a class and not two lines in the patch.</b> Vanilla asks two questions per face - "was I already
 * reached through the one opposite this" and "can the face I entered through see this one" - and both are bit tests.
 * The cost is not the tests, it is what they guard: a neighbour lookup that builds a position, checks the render
 * distance and searches the frustum's chunk array, six times per section. Computed as a mask first, only the faces
 * that survive are looked up, and on a typical section most do not.</p>
 *
 * <p>The masks are indexed by {@code EnumFacing.ordinal()}: DOWN, UP, NORTH, SOUTH, WEST, EAST, so the opposite of
 * face {@code i} is {@code i ^ 1} and reversing a mask is two shifts.</p>
 */
public final class SectionFaces {

    /** Every face. */
    public static final int ALL = 0x3F;

    /** The even ordinals: DOWN, NORTH, WEST. Their opposites are one bit above. */
    private static final int LOW_HALF = 0x15;

    /** The odd ordinals: UP, SOUTH, EAST. Their opposites are one bit below. */
    private static final int HIGH_HALF = 0x2A;

    private SectionFaces() {
    }

    /**
     * The mask with every face replaced by the one opposite it.
     *
     * <p>This is what vanilla's {@code hasDirection(facing.getOpposite())} asks one face at a time: a section reached
     * through {@code f} may not be expanded back out of {@code opposite(f)}, because that path leads to where it came
     * from.</p>
     */
    public static int opposite(int mask) {
        return ((mask & LOW_HALF) << 1) | ((mask & HIGH_HALF) >>> 1);
    }

    /**
     * The faces a queued section may expand into.
     *
     * <p>Both of vanilla's gates, as one mask. {@code setFacing} accumulates every face the section was reached
     * through, and a direction is refused when its opposite is in there; the visibility matrix then refuses any face
     * the entry face cannot see. A root - {@code enteredFrom} of -1 - has no entry face, and vanilla skips the matrix
     * for it, which is the same thing as being able to see everything.</p>
     *
     * @param setFacing     the faces this section was already reached through
     * @param enteredFrom   the face it was entered from, or -1 for a root
     * @param outOfEntered  the faces reachable out of {@code enteredFrom}, from the section's visibility matrix;
     *                      ignored for a root
     */
    public static int expandable(int setFacing, int enteredFrom, int outOfEntered) {
        int reachable = enteredFrom < 0 ? ALL : outOfEntered & ALL;
        return reachable & ~opposite(setFacing & ALL);
    }
}
