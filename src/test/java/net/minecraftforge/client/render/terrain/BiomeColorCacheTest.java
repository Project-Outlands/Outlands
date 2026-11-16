package net.minecraftforge.client.render.terrain;

import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.WorldType;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.BiomeColorHelper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The per-section biome colour table, against vanilla's own loop.
 *
 * <p><b>What is being pinned.</b> Not that the table is fast - that is what it is for - but that it answers with the
 * same integer vanilla computes, for every position in a section, and that it never answers with a colour from a
 * different section or a previous build. A tint that is off by one channel unit is invisible in a diff and obvious on
 * screen, and a stale tint after a rebuild is the kind of bug that only shows up when a biome changes.</p>
 */
class BiomeColorCacheTest {

    private static final int ORIGIN_X = -32;
    private static final int ORIGIN_Y = 64;
    private static final int ORIGIN_Z = 48;

    @AfterEach
    void endAnyBuild() {
        BiomeColorCache.endBuild();
    }

    @Test
    void everyPositionInTheSectionMatchesVanilla() {
        FakeWorld world = new FakeWorld(1);
        BiomeColorHelper.ColorResolver resolver = colorFromWorld(world);

        BiomeColorCache.beginBuild(world, ORIGIN_X, ORIGIN_Y, ORIGIN_Z);

        Random random = new Random(7);
        for (int i = 0; i < 500; i++) {
            int x = ORIGIN_X + random.nextInt(16);
            int y = ORIGIN_Y + random.nextInt(16);
            int z = ORIGIN_Z + random.nextInt(16);
            assertEquals(vanilla(world, x, y, z, resolver), BiomeColorCache.colorAtPos(world, new BlockPos(x, y, z), resolver),
                    "position " + x + "," + y + "," + z);
        }
    }

    @Test
    void aRepeatQueryCostsTheTableAndStillMatches() {
        // The case the table exists for: the same block asked again for another tinted quad. It has to answer, and it
        // has to answer the same thing.
        FakeWorld world = new FakeWorld(2);
        BiomeColorHelper.ColorResolver resolver = colorFromWorld(world);
        BlockPos pos = new BlockPos(ORIGIN_X + 3, ORIGIN_Y + 5, ORIGIN_Z + 9);

        BiomeColorCache.beginBuild(world, ORIGIN_X, ORIGIN_Y, ORIGIN_Z);
        int first = BiomeColorCache.colorAtPos(world, pos, resolver);
        int second = BiomeColorCache.colorAtPos(world, pos, resolver);

        assertEquals(first, second);
        assertEquals(vanilla(world, pos.getX(), pos.getY(), pos.getZ(), resolver), first);
    }

    @Test
    void aRebuildSeesTheNewColour() {
        FakeWorld world = new FakeWorld(3);
        BiomeColorHelper.ColorResolver resolver = colorFromWorld(world);
        BlockPos pos = new BlockPos(ORIGIN_X + 1, ORIGIN_Y + 1, ORIGIN_Z + 1);

        BiomeColorCache.beginBuild(world, ORIGIN_X, ORIGIN_Y, ORIGIN_Z);
        int before = BiomeColorCache.colorAtPos(world, pos, resolver);
        BiomeColorCache.endBuild();

        // Whatever makes a biome colour change - a biome edit, a resource reload, a mod replacing the resolver - the
        // next build is what bakes it, and that is exactly what vanilla would do.
        world.shift = 0x203040;
        BiomeColorCache.beginBuild(world, ORIGIN_X, ORIGIN_Y, ORIGIN_Z);
        int after = BiomeColorCache.colorAtPos(world, pos, resolver);

        assertEquals(vanilla(world, pos.getX(), pos.getY(), pos.getZ(), resolver), after);
        org.junit.jupiter.api.Assertions.assertNotEquals(before, after, "the table was reset with the build");
    }

    @Test
    void aPositionOutsideTheBuildIsComputedNotCached() {
        FakeWorld world = new FakeWorld(4);
        BiomeColorHelper.ColorResolver resolver = colorFromWorld(world);
        BiomeColorCache.beginBuild(world, ORIGIN_X, ORIGIN_Y, ORIGIN_Z);

        // Same local coordinate, one section over: it must not be answered from the table, and it must not fill the
        // table cell that belongs to the section being built.
        int outsideX = ORIGIN_X + 16 + 3;
        int y = ORIGIN_Y + 3;
        int z = ORIGIN_Z + 3;
        assertEquals(vanilla(world, outsideX, y, z, resolver),
                BiomeColorCache.colorAtPos(world, new BlockPos(outsideX, y, z), resolver));

        int insideX = ORIGIN_X + 3;
        assertEquals(vanilla(world, insideX, y, z, resolver),
                BiomeColorCache.colorAtPos(world, new BlockPos(insideX, y, z), resolver),
                "the outside query left the inside cell alone");
    }

    @Test
    void withoutABuildTheAnswerIsVanilla() {
        // Tile entity renderers and inventory models read the real world rather than a chunk view. There is no section
        // to table, and nothing may be answered from a build that already ended.
        FakeWorld world = new FakeWorld(5);
        BiomeColorHelper.ColorResolver resolver = colorFromWorld(world);
        BiomeColorCache.beginBuild(world, ORIGIN_X, ORIGIN_Y, ORIGIN_Z);
        BiomeColorCache.colorAtPos(world, new BlockPos(ORIGIN_X + 1, ORIGIN_Y + 1, ORIGIN_Z + 1), resolver);
        BiomeColorCache.endBuild();

        world.shift = 0x102030;
        BlockPos pos = new BlockPos(ORIGIN_X + 1, ORIGIN_Y + 1, ORIGIN_Z + 1);
        assertEquals(vanilla(world, pos.getX(), pos.getY(), pos.getZ(), resolver),
                BiomeColorCache.colorAtPos(world, pos, resolver));
    }

    /** Vanilla's loop, copied: two BlockPos bounds, an iterable, nine samples, integer division. */
    private static int vanilla(IBlockAccess view, int x, int y, int z, BiomeColorHelper.ColorResolver resolver) {
        int red = 0;
        int green = 0;
        int blue = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
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

    /**
     * A resolver that colours by position, so the test needs no {@link Biome} instances: what matters here is the
     * arithmetic and the caching, not which biome is where.
     */
    private static BiomeColorHelper.ColorResolver colorFromWorld(FakeWorld world) {
        return (biome, pos) -> {
            int hash = pos.getX() * 73856093 ^ pos.getZ() * 19349663;
            int color = (hash & 0xFFFFFF) ^ world.shift;
            return color & 0xFFFFFF;
        };
    }

    /** The smallest block access the resolver path needs. */
    private static final class FakeWorld implements IBlockAccess {
        /** Changes between builds, standing in for "the biome colour is different now". */
        private int shift;

        private FakeWorld(int seed) {
            this.shift = seed * 0x111111;
        }

        @Override
        public net.minecraft.tileentity.TileEntity getTileEntity(BlockPos pos) {
            return null;
        }

        @Override
        public int getCombinedLight(BlockPos pos, int lightValue) {
            return 0;
        }

        @Override
        public net.minecraft.block.state.IBlockState getBlockState(BlockPos pos) {
            return net.minecraft.init.Blocks.AIR.getDefaultState();
        }

        @Override
        public boolean isAirBlock(BlockPos pos) {
            return true;
        }

        @Override
        public Biome getBiome(BlockPos pos) {
            return null;
        }

        @Override
        public int getStrongPower(BlockPos pos, EnumFacing direction) {
            return 0;
        }

        @Override
        public WorldType getWorldType() {
            return WorldType.DEFAULT;
        }

        @Override
        public boolean isSideSolid(BlockPos pos, EnumFacing side, boolean _default) {
            return false;
        }
    }
}
