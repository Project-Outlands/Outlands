package net.minecraftforge.registries;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.block.BlockColored;
import net.minecraft.block.material.Material;
import net.minecraft.block.properties.PropertyBool;
import net.minecraft.block.properties.PropertyInteger;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.item.EnumDyeColor;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.profiler.Profiler;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.datafix.DataFixer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldProviderSurface;
import net.minecraft.world.chunk.BlockStateContainer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.chunk.NibbleArray;
import net.minecraft.world.chunk.storage.AnvilChunkLoader;
import net.minecraft.world.storage.WorldInfo;
import net.minecraftforge.fml.common.registry.ForgeTestRunner;
import net.minecraftforge.fml.common.registry.MinecraftTestHarness;
import net.minecraftforge.fml.relauncher.ReflectionHelper;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * A section is only written in the palette format when it has to be. Everything that the legacy
 * (block id + metadata) format can express stays byte-identical to vanilla, so a world saved by this
 * build keeps loading in unmodified 1.12 - the palette extension is the exception, not the rule.
 */
@RunWith(ForgeTestRunner.class)
@ForgeTestRunner.Isolated
public class SectionWriteFormatTest
{
    private static final PropertyInteger TEST_STATE = PropertyInteger.create("test_state", 0, 15);
    private static final PropertyBool FLAG = PropertyBool.create("flag");

    private static final int HIGH_ID = 300;
    private static final int WIDE_ID = 5000;
    private static final int BOUNDARY_LEGACY_ID = 4095;
    private static final int BOUNDARY_WIDE_ID = 4096;

    private static Block highIdBlock;
    private static Block wideBlock;
    private static Block lossyBlock;
    private static Block boundaryLegacyBlock;
    private static Block boundaryWideBlock;
    private static File saveLocation;
    private static Map<ResourceLocation, ForgeRegistry.Snapshot> originalSnapshots;

    @BeforeClass
    public static void bootstrap() throws IOException
    {
        MinecraftTestHarness.bootstrap();
        saveLocation = Files.createTempDirectory("outlands-section-format").toFile();
        originalSnapshots = new HashMap<>();
        RegistryManager.ACTIVE.registries.forEach((name, registry) -> originalSnapshots.put(name, registry.makeSnapshot()));

        ForgeRegistry<Block> registry = RegistryManager.ACTIVE.getRegistry(GameData.BLOCKS);
        highIdBlock = new MetaBlock(new ResourceLocation("test", "high_id"));
        wideBlock = new MetaBlock(new ResourceLocation("test", "wide"));
        lossyBlock = new LossyBlock(new ResourceLocation("test", "lossy"));
        boundaryLegacyBlock = new MetaBlock(new ResourceLocation("test", "boundary_legacy"));
        boundaryWideBlock = new MetaBlock(new ResourceLocation("test", "boundary_wide"));
        registry.add(HIGH_ID, highIdBlock);
        registry.add(WIDE_ID, wideBlock);
        registry.add(HIGH_ID + 1, lossyBlock);
        registry.add(BOUNDARY_LEGACY_ID, boundaryLegacyBlock);
        registry.add(BOUNDARY_WIDE_ID, boundaryWideBlock);
        GameData.freezeData();
    }

    @AfterClass
    public static void tearDown()
    {
        GameData.injectSnapshot(originalSnapshots, false, true);
    }

    @Test
    public void vanillaSectionWritesBlocksAndDataWithoutPalette()
    {
        IBlockState wool = Blocks.WOOL.getDefaultState().withProperty(BlockColored.COLOR, EnumDyeColor.RED);
        BlockStateContainer container = new BlockStateContainer();
        container.set(0, 0, 0, Blocks.STONE.getDefaultState());
        container.set(1, 0, 0, wool);
        container.set(2, 0, 0, Blocks.AIR.getDefaultState());

        byte[] blocks = new byte[4096];
        NibbleArray data = new NibbleArray();
        NibbleArray add = container.getDataForNBT(blocks, data);

        // No Add tag (all ids below 256) and - the point of this test - no Palette tag.
        assertNull(add);
        assertNull(container.getTemporaryPalette());

        // The real block id and metadata, not a palette index. Vanilla ids are a harness invariant.
        assertEquals(1, Block.getIdFromBlock(Blocks.STONE));
        assertEquals(35, Block.getIdFromBlock(Blocks.WOOL));
        assertEquals((byte) 1, blocks[0]);
        assertEquals(0, data.get(0, 0, 0));
        assertEquals((byte) 35, blocks[1]);
        assertEquals(EnumDyeColor.RED.getMetadata(), data.get(1, 0, 0));
        assertEquals(0, blocks[2]);
        assertEquals(0, data.get(2, 0, 0));
    }

    @Test
    public void legacySectionRoundTripsWithoutPalette()
    {
        IBlockState wool = Blocks.WOOL.getDefaultState().withProperty(BlockColored.COLOR, EnumDyeColor.RED);
        BlockStateContainer container = new BlockStateContainer();
        container.set(0, 0, 0, Blocks.STONE.getDefaultState());
        container.set(1, 0, 0, wool);

        byte[] blocks = new byte[4096];
        NibbleArray data = new NibbleArray();
        NibbleArray add = container.getDataForNBT(blocks, data);

        BlockStateContainer decoded = new BlockStateContainer();
        decoded.setTemporaryPalette(null);
        decoded.setDataFromNBT(blocks, data, add);

        assertSame(Blocks.STONE.getDefaultState(), decoded.get(0, 0, 0));
        assertSame(wool, decoded.get(1, 0, 0));
        assertSame(Blocks.AIR.getDefaultState(), decoded.get(2, 0, 0));
    }

    @Test
    public void blockIdsBetween256And4095WriteTheAddNibble()
    {
        IBlockState state = highIdBlock.getStateFromMeta(7);
        BlockStateContainer container = new BlockStateContainer();
        container.set(0, 0, 0, state);

        byte[] blocks = new byte[4096];
        NibbleArray data = new NibbleArray();
        NibbleArray add = container.getDataForNBT(blocks, data);

        assertNull(container.getTemporaryPalette());
        assertNotNull(add);
        assertEquals((byte) (HIGH_ID & 255), blocks[0]);
        assertEquals(0, blocks[1]);
        assertEquals(HIGH_ID >> 8, add.get(0, 0, 0));
        assertEquals(0, add.get(1, 0, 0));
        assertEquals(7, data.get(0, 0, 0));

        BlockStateContainer decoded = new BlockStateContainer();
        decoded.setDataFromNBT(blocks, data, add);
        assertSame(state, decoded.get(0, 0, 0));
    }

    @Test
    public void idsBelowTheTwelveBitLimitStayLegacy()
    {
        IBlockState state = boundaryLegacyBlock.getStateFromMeta(15);
        BlockStateContainer container = new BlockStateContainer();
        container.set(0, 0, 0, state);

        byte[] blocks = new byte[4096];
        NibbleArray data = new NibbleArray();
        NibbleArray add = container.getDataForNBT(blocks, data);

        assertNull(container.getTemporaryPalette());
        assertNotNull(add);

        BlockStateContainer decoded = new BlockStateContainer();
        decoded.setDataFromNBT(blocks, data, add);
        assertSame(state, decoded.get(0, 0, 0));
    }

    @Test
    public void idsAboveTheTwelveBitLimitFallBackToThePalette()
    {
        IBlockState boundary = boundaryWideBlock.getStateFromMeta(0);
        IBlockState wide = wideBlock.getStateFromMeta(15);
        BlockStateContainer container = new BlockStateContainer();
        container.set(0, 0, 0, boundary);
        container.set(1, 0, 0, wide);

        byte[] blocks = new byte[4096];
        NibbleArray data = new NibbleArray();
        NibbleArray add = container.getDataForNBT(blocks, data);

        assertNull(add);
        int[] palette = container.getTemporaryPalette();
        assertNotNull(palette);
        assertEquals(BOUNDARY_WIDE_ID << 4, palette[paletteIndex(blocks, data, 0)]);
        assertEquals(WIDE_ID << 4 | 15, palette[paletteIndex(blocks, data, 1)]);

        BlockStateContainer decoded = new BlockStateContainer();
        decoded.setTemporaryPalette(palette);
        decoded.setDataFromNBT(blocks, data, null);
        assertSame(boundary, decoded.get(0, 0, 0));
        assertSame(wide, decoded.get(1, 0, 0));
        assertSame(Blocks.AIR.getDefaultState(), decoded.get(2, 0, 0));
    }

    /**
     * A block with two states that share one metadata value. 1.12 state ids are {@code (block id, meta)}
     * pairs, so the second state overwrites the first in the id map and the container already returns the
     * canonical one - the collision never even reaches the writer. The section is therefore written as
     * legacy, and the same canonicalisation happens through the palette. Pinned here because a name-based
     * palette (planned) would make such states distinguishable and change this answer.
     */
    @Test
    public void statesWithCollidingMetadataAreCanonicalisedBeforeTheWriter()
    {
        IBlockState withFlag = lossyBlock.getDefaultState().withProperty(FLAG, true);
        BlockStateContainer container = new BlockStateContainer();
        container.set(0, 0, 0, withFlag);

        assertSame(lossyBlock.getDefaultState(), container.get(0, 0, 0));

        byte[] blocks = new byte[4096];
        NibbleArray data = new NibbleArray();
        NibbleArray add = container.getDataForNBT(blocks, data);

        assertNull(container.getTemporaryPalette());
        assertEquals((byte) ((HIGH_ID + 1) & 255), blocks[0]);
        assertEquals((HIGH_ID + 1) >> 8, add.get(0, 0, 0));
    }

    @Test
    public void savedChunkOmitsPaletteTagForVanillaSections() throws Exception
    {
        TestWorld world = new TestWorld();
        Chunk chunk = new Chunk(world, 0, 0);
        chunk.setBlockState(new BlockPos(0, 32, 0), Blocks.STONE.getDefaultState());
        chunk.setBlockState(new BlockPos(1, 32, 0), Blocks.WOOL.getDefaultState().withProperty(BlockColored.COLOR, EnumDyeColor.RED));

        NBTTagCompound level = writeChunk(world, chunk);

        NBTTagCompound section = findSection(level, 2);
        assertFalse("a vanilla section must not grow a Palette tag", section.hasKey("Palette"));
        assertEquals((byte) 1, section.getByteArray("Blocks")[0]);
        assertEquals(0, new NibbleArray(section.getByteArray("Data")).get(0, 0, 0));
        assertEquals((byte) 35, section.getByteArray("Blocks")[1]);

        Chunk reloaded = new ReadableAnvilChunkLoader().read(world, level);
        assertSame(Blocks.STONE.getDefaultState(), reloaded.getBlockState(new BlockPos(0, 32, 0)));
    }

    @Test
    public void savedChunkWritesPaletteTagForWideSections() throws Exception
    {
        TestWorld world = new TestWorld();
        Chunk chunk = new Chunk(world, 0, 0);
        IBlockState wide = wideBlock.getStateFromMeta(15);
        chunk.setBlockState(new BlockPos(0, 32, 0), wide);

        NBTTagCompound level = writeChunk(world, chunk);

        NBTTagCompound section = findSection(level, 2);
        assertTrue("a section with ids over 4095 needs the palette", section.hasKey("Palette", 11));
        int[] palette = section.getIntArray("Palette");
        int index = paletteIndex(section.getByteArray("Blocks"), new NibbleArray(section.getByteArray("Data")), 0);
        assertEquals(WIDE_ID << 4 | 15, palette[index]);

        Chunk reloaded = new ReadableAnvilChunkLoader().read(world, level);
        assertSame(wide, reloaded.getBlockState(new BlockPos(0, 32, 0)));
    }

    /** The real writer, without the region file: {@code saveChunk} needs an FML sided delegate, this does not. */
    private static NBTTagCompound writeChunk(TestWorld world, Chunk chunk) throws Exception
    {
        NBTTagCompound level = new NBTTagCompound();
        AnvilChunkLoader loader = new AnvilChunkLoader(saveLocation, new DataFixer(1343));
        // Tests run on the MCP-named tree, so the dev name is the one on the class; null falls back to it.
        Method write = ReflectionHelper.findMethod(AnvilChunkLoader.class, "writeChunkToNBT", (String) null,
                Chunk.class, World.class, NBTTagCompound.class);
        write.invoke(loader, chunk, world, level);
        return level;
    }

    private static NBTTagCompound findSection(NBTTagCompound level, int y)
    {
        NBTTagList sections = level.getTagList("Sections", 10);
        List<Integer> seen = new ArrayList<>();
        for (int i = 0; i < sections.tagCount(); i++)
        {
            NBTTagCompound section = sections.getCompoundTagAt(i);
            int sectionY = section.getByte("Y") & 255;
            seen.add(sectionY);
            if (sectionY == y)
            {
                return section;
            }
        }
        throw new AssertionError("no section at y=" + y + ", saw " + seen);
    }

    private static int paletteIndex(byte[] blocks, NibbleArray data, int index)
    {
        return ((blocks[index] & 255) << 4) | data.get(index & 15, index >> 8 & 15, index >> 4 & 15);
    }

    private static class TestWorld extends World
    {
        private TestWorld()
        {
            super(null, new WorldInfo(new NBTTagCompound()), new WorldProviderSurface(), new Profiler(), false);
        }

        @Override
        protected IChunkProvider createChunkProvider()
        {
            return null;
        }

        @Override
        protected boolean isChunkLoaded(int x, int z, boolean allowEmpty)
        {
            return false;
        }

        @Override
        public void checkSessionLock()
        {
        }
    }

    private static class ReadableAnvilChunkLoader extends AnvilChunkLoader
    {
        private ReadableAnvilChunkLoader()
        {
            super(saveLocation, new DataFixer(1343));
        }

        private Chunk read(World world, NBTTagCompound level)
        {
            NBTTagCompound root = new NBTTagCompound();
            root.setTag("Level", level);
            return this.checkedReadChunkFromNBT(world, level.getInteger("xPos"), level.getInteger("zPos"), root);
        }
    }

    private static class MetaBlock extends Block
    {
        private MetaBlock(ResourceLocation name)
        {
            super(Material.ROCK);
            setRegistryName(name);
        }

        @Override
        protected net.minecraft.block.state.BlockStateContainer createBlockState()
        {
            return new net.minecraft.block.state.BlockStateContainer(this, TEST_STATE);
        }

        @Override
        public int getMetaFromState(IBlockState state)
        {
            return state.getValue(TEST_STATE);
        }

        @Override
        public IBlockState getStateFromMeta(int meta)
        {
            return this.getDefaultState().withProperty(TEST_STATE, meta);
        }
    }

    /**
     * Two states, one metadata value: {@link #FLAG} is not part of the meta, so the state cannot be
     * identified by {@code (id, meta)} alone.
     */
    private static class LossyBlock extends Block
    {
        private LossyBlock(ResourceLocation name)
        {
            super(Material.ROCK);
            setRegistryName(name);
        }

        @Override
        protected net.minecraft.block.state.BlockStateContainer createBlockState()
        {
            return new net.minecraft.block.state.BlockStateContainer(this, TEST_STATE, FLAG);
        }

        @Override
        public int getMetaFromState(IBlockState state)
        {
            return state.getValue(TEST_STATE);
        }

        @Override
        public IBlockState getStateFromMeta(int meta)
        {
            return this.getDefaultState().withProperty(TEST_STATE, meta);
        }
    }
}
