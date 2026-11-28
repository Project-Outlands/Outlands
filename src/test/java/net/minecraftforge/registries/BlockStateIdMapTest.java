package net.minecraftforge.registries;

import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.block.BlockColored;
import net.minecraft.block.material.Material;
import net.minecraft.block.properties.PropertyInteger;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.item.EnumDyeColor;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.BitArray;
import net.minecraft.util.ObjectIntIdentityMap;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.chunk.BlockStateContainer;
import net.minecraft.world.chunk.ChunkPrimer;
import net.minecraft.world.chunk.NibbleArray;
import net.minecraftforge.fml.common.registry.ForgeTestRunner;
import net.minecraftforge.fml.common.registry.MinecraftTestHarness;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

@RunWith(ForgeTestRunner.class)
@ForgeTestRunner.Isolated
public class BlockStateIdMapTest
{
    private static final PropertyInteger TEST_STATE = PropertyInteger.create("test_state", 0, 15);
    private static final int MAX_STATE_BLOCK_ID = 0x0FFFFFFF;

    private static Block lowIdBlock;
    private static Block boundaryLegacyBlock;
    private static Block boundaryWideBlock;
    private static Block wideBlock;
    private static Block boundaryStateBlock;
    private static Block overflowBlock;
    private static IllegalArgumentException overflowRegistrationFailure;
    private static IBlockState airStateAfterOverflowRegistration;

    @BeforeClass
    public static void bootstrap()
    {
        MinecraftTestHarness.bootstrap();
        ForgeRegistry<Block> registry = RegistryManager.ACTIVE.getRegistry(GameData.BLOCKS);
        lowIdBlock = new MetadataBlock(new ResourceLocation("test", "low_id"));
        boundaryLegacyBlock = new MetadataBlock(new ResourceLocation("test", "boundary_legacy"));
        boundaryWideBlock = new MetadataBlock(new ResourceLocation("test", "boundary_wide"));
        wideBlock = new MetadataBlock(new ResourceLocation("test", "wide"));
        boundaryStateBlock = new MetadataBlock(new ResourceLocation("test", "boundary_state"));
        overflowBlock = new MetadataBlock(new ResourceLocation("test", "overflow"));
        registry.add(300, lowIdBlock);
        registry.add(4095, boundaryLegacyBlock);
        registry.add(4096, boundaryWideBlock);
        registry.add(5000, wideBlock);
        registry.add(MAX_STATE_BLOCK_ID, boundaryStateBlock);
        try
        {
            registry.add(MAX_STATE_BLOCK_ID + 1, overflowBlock);
        }
        catch (IllegalArgumentException e)
        {
            overflowRegistrationFailure = e;
        }
        airStateAfterOverflowRegistration = Block.BLOCK_STATE_IDS.getByValue(0);
    }

    @Test
    public void objectIntIdentityMapRetainsNegativeIds()
    {
        ObjectIntIdentityMap<String> map = new ObjectIntIdentityMap<>();
        map.put("negative", Integer.MIN_VALUE);
        map.put("positive", 2);

        assertEquals(Integer.MIN_VALUE, map.get("negative"));
        assertSame("negative", map.getByValue(Integer.MIN_VALUE));
        assertEquals(2, map.get("positive"));
        assertSame("positive", map.getByValue(2));
        assertEquals(2, map.size());

        List<String> iterated = new ArrayList<>();
        for (String value : map)
        {
            iterated.add(value);
        }
        assertEquals(2, iterated.size());
        assertTrue(iterated.contains("negative"));
        assertTrue(iterated.contains("positive"));
    }

    @Test
    public void clearableIdentityMapRemovesNegativeIds()
    {
        GameData.ClearableObjectIntIdentityMap<String> map = new GameData.ClearableObjectIntIdentityMap<>();
        map.put("negative", -1);
        map.put("positive", 1);
        map.remove("negative");

        assertNull(map.getByValue(-1));
        assertSame("positive", map.getByValue(1));
        assertEquals(1, map.size());

        map.put("negative", -1);
        map.clear();
        assertNull(map.getByValue(-1));
        assertNull(map.getByValue(1));
        assertEquals(0, map.size());
    }

    @Test
    public void stateIdsUseBothReidLayouts()
    {
        assertStateRoundTrip(lowIdBlock, 7, 300 + (7 << 12));
        assertStateRoundTrip(boundaryLegacyBlock, 15, 65535);
        assertStateRoundTrip(boundaryWideBlock, 0, 65536);
        assertStateRoundTrip(wideBlock, 15, (5000 << 4) | 15);
    }

    @Test
    public void maximumBlockIdRoundTripsNegativeStateIds()
    {
        IBlockState firstState = boundaryStateBlock.getStateFromMeta(0);
        IBlockState lastState = boundaryStateBlock.getStateFromMeta(15);
        assertStateRoundTrip(boundaryStateBlock, 0, -16);
        assertStateRoundTrip(boundaryStateBlock, 15, -1);
        assertSame(firstState, Block.BLOCK_STATE_IDS.getByValue(-16));
        assertSame(lastState, Block.BLOCK_STATE_IDS.getByValue(-1));
        assertEquals(-1, Block.BLOCK_STATE_IDS.get(lastState));
    }

    @Test
    public void blockRegistrationRejectsIdsAbove28BitsWithoutOverwritingAir()
    {
        assertNotNull(overflowRegistrationFailure);
        assertTrue(overflowRegistrationFailure.getMessage().contains(overflowBlock.getRegistryName().toString()));
        assertTrue(overflowRegistrationFailure.getMessage().contains(Integer.toString(MAX_STATE_BLOCK_ID + 1)));
        assertSame(Blocks.AIR.getDefaultState(), airStateAfterOverflowRegistration);
        assertSame(Blocks.AIR.getDefaultState(), Block.BLOCK_STATE_IDS.getByValue(0));
    }

    @Test
    public void stateIdRejectsIdsAbove28Bits()
    {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> Block.getStateId(overflowBlock.getDefaultState()));
        assertTrue(failure.getMessage().contains(overflowBlock.getRegistryName().toString()));
        assertTrue(failure.getMessage().contains(Integer.toString(MAX_STATE_BLOCK_ID + 1)));
    }

    @Test
    public void bitArray32BitsPreservesSignedIntPatterns()
    {
        int[] values = {0, 1, -1, Integer.MIN_VALUE, Integer.MAX_VALUE, 0x55555555, 0xAAAAAAAA};
        BitArray bits = new BitArray(32, values.length);
        for (int i = 0; i < values.length; i++)
        {
            bits.setAt(i, values[i]);
        }
        for (int i = 0; i < values.length; i++)
        {
            assertEquals(values[i], bits.getAt(i));
        }
    }

    @Test
    public void globalPaletteRoundTripsNegativeStateId()
    {
        IBlockState negativeState = boundaryStateBlock.getStateFromMeta(15);
        BlockStateContainer encoded = new BlockStateContainer();
        int index = 0;
        for (IBlockState state : Block.BLOCK_STATE_IDS)
        {
            if (index == 300)
            {
                break;
            }
            encoded.set(index & 15, index >> 8, index >> 4 & 15, state);
            index++;
        }
        encoded.set(15, 15, 15, negativeState);
        assertSame(negativeState, encoded.get(15, 15, 15));

        PacketBuffer buffer = new PacketBuffer(Unpooled.buffer());
        try
        {
            encoded.write(buffer);
            BlockStateContainer decoded = new BlockStateContainer();
            decoded.read(buffer);
            assertSame(negativeState, decoded.get(15, 15, 15));
            assertEquals(0, buffer.readableBytes());
        }
        finally
        {
            buffer.release();
        }
    }

    @Test
    public void reidPaletteSurvivesNbtRoundTrip()
    {
        IBlockState lowState = lowIdBlock.getStateFromMeta(7);
        IBlockState wideState = wideBlock.getStateFromMeta(15);
        BlockStateContainer encoded = new BlockStateContainer();
        encoded.set(0, 0, 0, lowState);
        encoded.set(1, 0, 0, wideState);
        encoded.set(2, 0, 0, Blocks.AIR.getDefaultState());

        byte[] blockIds = new byte[4096];
        NibbleArray data = new NibbleArray();
        NibbleArray add = encoded.getDataForNBT(blockIds, data);
        assertNull(add);

        NBTTagCompound section = new NBTTagCompound();
        section.setByteArray("Blocks", blockIds);
        section.setByteArray("Data", data.getData());
        section.setIntArray("Palette", encoded.getTemporaryPalette());

        BlockStateContainer decoded = new BlockStateContainer();
        decoded.setTemporaryPalette(section.hasKey("Palette", 11) ? section.getIntArray("Palette") : null);
        decoded.setDataFromNBT(section.getByteArray("Blocks"), new NibbleArray(section.getByteArray("Data")), null);

        assertSame(lowState, decoded.get(0, 0, 0));
        assertSame(wideState, decoded.get(1, 0, 0));
        assertSame(Blocks.AIR.getDefaultState(), decoded.get(2, 0, 0));
        assertNull(decoded.getTemporaryPalette());
    }

    @Test
    public void vanillaNbtWithoutPaletteStillDecodes()
    {
        IBlockState state = Blocks.WOOL.getDefaultState().withProperty(BlockColored.COLOR, EnumDyeColor.RED);
        int stateId = Block.getIdFromBlock(state.getBlock()) << 4 | state.getBlock().getMetaFromState(state);
        byte[] blockIds = new byte[4096];
        NibbleArray data = new NibbleArray();
        blockIds[0] = (byte)(stateId >> 4);
        data.set(0, 0, 0, stateId & 15);

        BlockStateContainer decoded = new BlockStateContainer();
        decoded.setDataFromNBT(blockIds, data, null);

        assertSame(state, decoded.get(0, 0, 0));
        assertSame(Blocks.AIR.getDefaultState(), decoded.get(1, 0, 0));
    }

    @Test
    public void neidNbtCombinesAddAndAdd2()
    {
        IBlockState state = wideBlock.getStateFromMeta(15);
        int stateId = 5000 << 4 | 15;
        byte[] blockIds = new byte[4096];
        NibbleArray data = new NibbleArray();
        NibbleArray add = new NibbleArray();
        NibbleArray add2 = new NibbleArray();
        blockIds[0] = (byte)(stateId >> 4);
        data.set(0, 0, 0, stateId & 15);
        add.set(0, 0, 0, stateId >> 12 & 15);
        add2.set(0, 0, 0, stateId >> 16 & 15);

        BlockStateContainer decoded = new BlockStateContainer();
        decoded.setLegacyAdd2(add2);
        decoded.setDataFromNBT(blockIds, data, add);

        assertSame(state, decoded.get(0, 0, 0));
        assertSame(Blocks.AIR.getDefaultState(), decoded.get(1, 0, 0));
    }

    @Test
    public void chunkPrimerStoresWideStates()
    {
        IBlockState wideState = wideBlock.getStateFromMeta(15);
        ChunkPrimer primer = new ChunkPrimer();
        primer.setBlockState(1, 100, 2, lowIdBlock.getStateFromMeta(7));
        primer.setBlockState(1, 200, 2, wideState);
        primer.setBlockState(1, 0, 3, wideState);

        assertSame(wideState, primer.getBlockState(1, 200, 2));
        assertEquals(1, primer.findGroundBlockIdx(1, 2));
    }

    private static void assertStateRoundTrip(Block block, int meta, int expectedId)
    {
        IBlockState state = block.getStateFromMeta(meta);
        int id = Block.getStateId(state);
        assertEquals(expectedId, id);
        assertSame(state, Block.getStateById(id));
    }

    private static class MetadataBlock extends Block
    {
        private MetadataBlock(ResourceLocation name)
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
}
