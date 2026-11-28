package net.minecraft.entity.monster;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.properties.PropertyInteger;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.util.Constants;
import net.minecraftforge.fml.common.registry.ForgeTestRunner;
import net.minecraftforge.fml.common.registry.MinecraftTestHarness;
import net.minecraftforge.registries.GameData;
import net.minecraftforge.registries.WideTestRegistrations;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

@RunWith(ForgeTestRunner.class)
@ForgeTestRunner.Isolated
public class EntityEndermanTest
{
    private static final PropertyInteger TEST_STATE = PropertyInteger.create("test_state", 0, 15);

    private static Block legacyBlock;
    private static Block wideBlock;

    @BeforeClass
    public static void bootstrap()
    {
        MinecraftTestHarness.bootstrap();
        legacyBlock = new MetadataBlock(new ResourceLocation("test", "legacy_enderman"));
        wideBlock = new MetadataBlock(new ResourceLocation("test", "wide_enderman"));
        WideTestRegistrations.registerBlock(300, legacyBlock);
        WideTestRegistrations.registerBlock(5000, wideBlock);
        GameData.freezeData();
    }

    @Test
    public void carriedBlockNbtRoundTripsWideIdAndMetadata()
    {
        IBlockState state = wideBlock.getStateFromMeta(15);
        EntityEnderman original = new EntityEnderman(null);
        original.setHeldBlockState(state);
        NBTTagCompound nbt = new NBTTagCompound();
        original.writeEntityToNBT(nbt);

        assertTrue(nbt.hasKey("carried", Constants.NBT.TAG_INT));
        assertTrue(nbt.hasKey("carriedData", Constants.NBT.TAG_SHORT));
        assertEquals(5000, nbt.getInteger("carried"));
        assertEquals(15, nbt.getShort("carriedData"));

        EntityEnderman decoded = new EntityEnderman(null);
        decoded.readEntityFromNBT(nbt);
        assertSame(state, decoded.getHeldBlockState());
    }

    @Test
    public void carriedBlockNbtReadsLegacyShort()
    {
        IBlockState state = legacyBlock.getStateFromMeta(7);
        NBTTagCompound nbt = new NBTTagCompound();
        nbt.setShort("carried", (short)Block.getIdFromBlock(legacyBlock));
        nbt.setShort("carriedData", (short)7);

        EntityEnderman decoded = new EntityEnderman(null);
        decoded.readEntityFromNBT(nbt);
        assertSame(state, decoded.getHeldBlockState());
    }

    @Test
    public void carriedBlockNbtReadsStringName()
    {
        IBlockState state = wideBlock.getStateFromMeta(9);
        NBTTagCompound nbt = new NBTTagCompound();
        nbt.setString("carried", wideBlock.getRegistryName().toString());
        nbt.setShort("carriedData", (short)9);

        EntityEnderman decoded = new EntityEnderman(null);
        decoded.readEntityFromNBT(nbt);
        assertSame(state, decoded.getHeldBlockState());
    }

    private static class MetadataBlock extends Block
    {
        private MetadataBlock(ResourceLocation name)
        {
            super(Material.ROCK);
            setRegistryName(name);
        }

        @Override
        protected BlockStateContainer createBlockState()
        {
            return new BlockStateContainer(this, TEST_STATE);
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
