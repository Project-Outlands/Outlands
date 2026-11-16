package net.minecraftforge.client.render.terrain;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.biome.BiomeColorHelper;

/**
 * The per-section table vanilla rebuilds one call at a time, kept for the length of one chunk build.
 *
 * <p><b>What vanilla does per call.</b> {@code BiomeColorHelper.getColorAtPos} averages a 3x3 neighbourhood of biome
 * colours, and the block model renderer asks it once per tinted quad - not once per block. Each call builds two
 * {@code BlockPos} bounds, walks them through {@code BlockPos.getAllInBoxMutable} (an iterable, an iterator and a
 * mutable pos), and makes nine {@code getBiome} and nine colour-resolver calls. A grass block asks three times, a leaf
 * block six, and every one of those repeats the same nine samples for the same position.</p>
 *
 * <p><b>Why a table and not a map.</b> The query positions of one chunk build all fall inside one 16x16x16 section, so
 * the natural key is the local coordinate - a bit mask and an array index, no hashing. A lazily filled 4096-entry
 * table per colour resolver costs 16 KiB per thread and turns the repeat calls into one array read.</p>
 *
 * <p><b>Why it cannot go stale.</b> A biome colour is consumed only while meshing, and a mesh is only re-coloured when
 * it is rebuilt. {@link #beginBuild} throws the table away at the start of every build, so the answer is exactly as
 * fresh as vanilla's: whatever {@code getBiome} says during this build is what gets baked. There is no frame stamp and
 * no cross-build reuse to reason about.</p>
 *
 * <p><b>Why thread-local.</b> 1.12 builds chunks on the {@code Chunk Batcher} workers, each of which builds one chunk
 * at a time. A per-thread table needs no locking and cannot be observed by another build.</p>
 */
public final class BiomeColorCache {

    /** Side of the section a build covers. */
    private static final int SECTION = 16;
    private static final int SECTION_AREA = SECTION * SECTION;
    private static final int SECTION_VOLUME = SECTION_AREA * SECTION;

    private static final ThreadLocal<Build> BUILD = ThreadLocal.withInitial(Build::new);

    private BiomeColorCache() {}

    /**
     * Starts a build: everything cached so far belongs to a previous section and is dropped.
     *
     * @param view   the block access the build reads, which is the {@code RenderChunk}'s own cache
     * @param origin the section's minimum corner
     */
    public static void beginBuild(IBlockAccess view, int originX, int originY, int originZ) {
        Build build = BUILD.get();
        build.active = view != null;
        build.view = view;
        build.originX = originX;
        build.originY = originY;
        build.originZ = originZ;
        for (Slot slot : build.slots) {
            if (slot != null) {
                slot.reset();
            }
        }
    }

    /** Ends a build, so a caller that is not meshing never sees a cached answer. */
    public static void endBuild() {
        Build build = BUILD.get();
        build.active = false;
        build.view = null;
    }

    /**
     * The colour of {@code pos} under {@code resolver}, cached when a build is in progress and computed directly
     * otherwise.
     *
     * <p>The position has to be inside the section the build covers. A caller outside it - a tile entity renderer, an
     * inventory model, anything reading the real world rather than a chunk's view - takes the uncached path, so the
     * table can never answer for a position it was not built for.</p>
     */
    public static int colorAtPos(IBlockAccess view, BlockPos pos, BiomeColorHelper.ColorResolver resolver) {
        Build build = BUILD.get();
        int x = pos.getX();
        int y = pos.getY();
        int z = pos.getZ();
        if (!build.active || build.view != view
                || x < build.originX || x >= build.originX + SECTION
                || y < build.originY || y >= build.originY + SECTION
                || z < build.originZ || z >= build.originZ + SECTION) {
            return compute(view, pos, resolver);
        }

        Slot slot = build.slotFor(resolver);
        int index = ((x - build.originX) << 8) | ((z - build.originZ) << 4) | (y - build.originY);
        if (slot.isFilled(index)) {
            return slot.values[index];
        }
        int color = compute(view, pos, resolver);
        slot.values[index] = color;
        slot.fill(index);
        return color;
    }

    /**
     * Vanilla's own computation: the 3x3 horizontal average, with one reused mutable pos instead of the iterable.
     *
     * <p>The arithmetic is copied rather than re-derived, including the per-channel {@code & 0xFF} and the integer
     * division, because a rounded-off version of this would tint every grass block in the world very slightly wrong.</p>
     */
    private static int compute(IBlockAccess view, BlockPos pos, BiomeColorHelper.ColorResolver resolver) {
        int x = pos.getX();
        int y = pos.getY();
        int z = pos.getZ();
        int red = 0;
        int green = 0;
        int blue = 0;
        BlockPos.MutableBlockPos cursor = CURSOR.get();
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                cursor.setPos(x + dx, y, z + dz);
                int color = resolver.getColorAtPos(view.getBiome(cursor), cursor);
                red += (color & 0xFF0000) >> 16;
                green += (color & 0xFF00) >> 8;
                blue += color & 0xFF;
            }
        }
        return (red / 9 & 0xFF) << 16 | (green / 9 & 0xFF) << 8 | blue / 9 & 0xFF;
    }

    private static final ThreadLocal<BlockPos.MutableBlockPos> CURSOR =
            ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);

    /** One thread's table for the build it is running, one slot per colour resolver. */
    private static final class Build {
        private boolean active;
        private IBlockAccess view;
        private int originX;
        private int originY;
        private int originZ;
        private final Slot[] slots = new Slot[3];

        private Slot slotFor(BiomeColorHelper.ColorResolver resolver) {
            for (int i = 0; i < this.slots.length; i++) {
                Slot slot = this.slots[i];
                if (slot != null && slot.resolver == resolver) {
                    return slot;
                }
                if (slot == null) {
                    this.slots[i] = new Slot(resolver);
                    return this.slots[i];
                }
            }
            // More resolvers than the three vanilla ones: replace the first rather than grow, because a table that
            // keeps being rebuilt is still correct and this only happens for a mod that added a fourth tint channel.
            this.slots[0] = new Slot(resolver);
            return this.slots[0];
        }
    }

    /** A lazily filled colour table plus the bitset that says which cells were computed. */
    private static final class Slot {
        private final BiomeColorHelper.ColorResolver resolver;
        private final int[] values = new int[SECTION_VOLUME];
        private final long[] filled = new long[SECTION_VOLUME / Long.SIZE];

        private Slot(BiomeColorHelper.ColorResolver resolver) {
            this.resolver = resolver;
        }

        private boolean isFilled(int index) {
            return (this.filled[index >>> 6] & (1L << (index & 63))) != 0;
        }

        private void fill(int index) {
            this.filled[index >>> 6] |= 1L << (index & 63);
        }

        private void reset() {
            java.util.Arrays.fill(this.filled, 0L);
        }
    }
}
