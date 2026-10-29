package net.minecraftforge.registries;

import io.netty.buffer.Unpooled;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.server.SPacketBlockAction;
import net.minecraft.network.play.server.SPacketEntityEffect;
import net.minecraft.network.play.server.SPacketRemoveEntityEffect;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.common.util.Constants;
import net.minecraftforge.fml.common.registry.ForgeTestRunner;
import net.minecraftforge.fml.common.registry.MinecraftTestHarness;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

@RunWith(ForgeTestRunner.class)
@ForgeTestRunner.Isolated
public class WidePotionTest
{
    private static Potion legacyPotion;
    private static Potion widePotion;
    private static Block wideBlock;

    @BeforeClass
    public static void bootstrap()
    {
        MinecraftTestHarness.bootstrap();
        ForgeRegistry<Potion> potions = RegistryManager.ACTIVE.getRegistry(GameData.POTIONS);
        ForgeRegistry<Block> blocks = RegistryManager.ACTIVE.getRegistry(GameData.BLOCKS);
        legacyPotion = new TestPotion(new ResourceLocation("test", "legacy_potion"));
        widePotion = new TestPotion(new ResourceLocation("test", "wide_potion"));
        wideBlock = new TestBlock(new ResourceLocation("test", "wide_block_action"));
        potions.add(255, legacyPotion);
        potions.add(300, widePotion);
        blocks.add(5000, wideBlock);
        GameData.freezeData();
    }

    @Test
    public void potionEffectNbtRetainsWideIdAndEffectSemantics()
    {
        PotionEffect original = new PotionEffect(widePotion, 1234, 3, true, false);
        NBTTagCompound nbt = original.writeCustomPotionEffectToNBT(new NBTTagCompound());

        assertTrue(nbt.hasKey("Id", Constants.NBT.TAG_INT));
        PotionEffect decoded = PotionEffect.readCustomPotionEffectFromNBT(nbt);
        assertNotNull(decoded);
        assertSame(widePotion, decoded.getPotion());
        assertEquals(1234, decoded.getDuration());
        assertEquals(3, decoded.getAmplifier());
        assertTrue(decoded.getIsAmbient());
        assertFalse(decoded.doesShowParticles());
    }

    @Test
    public void legacyBytePotionEffectNbtReadsUnsignedId()
    {
        NBTTagCompound nbt = new NBTTagCompound();
        nbt.setByte("Id", (byte)255);

        PotionEffect decoded = PotionEffect.readCustomPotionEffectFromNBT(nbt);

        assertNotNull(decoded);
        assertSame(legacyPotion, decoded.getPotion());
    }

    @Test
    public void entityEffectPacketRetainsWidePotionId() throws Exception
    {
        SPacketEntityEffect original = new SPacketEntityEffect(42, new PotionEffect(widePotion, 1234, 3, true, false));
        PacketBuffer buffer = new PacketBuffer(Unpooled.buffer());
        try
        {
            original.writePacketData(buffer);
            assertEquals(42, buffer.readVarInt());
            assertEquals(300, buffer.readVarInt());
            buffer.readerIndex(0);
            SPacketEntityEffect decoded = new SPacketEntityEffect();
            decoded.readPacketData(buffer);

            assertEquals(42, decoded.getEntityId());
            byte legacyEffectId = decoded.getEffectId();
            assertEquals((byte)300, legacyEffectId);
            assertEquals(300, decoded.getEffectIdInt());
            assertEquals(1234, decoded.getDuration());
            assertEquals(3, decoded.getAmplifier());
            assertTrue(decoded.getIsAmbient());
            assertFalse(decoded.doesShowParticles());
            assertEquals(0, buffer.readableBytes());
        }
        finally
        {
            buffer.release();
        }
    }

    @Test
    public void removeEntityEffectPacketRetainsWidePotionId() throws Exception
    {
        SPacketRemoveEntityEffect original = new SPacketRemoveEntityEffect(42, widePotion);
        PacketBuffer buffer = new PacketBuffer(Unpooled.buffer());
        try
        {
            original.writePacketData(buffer);
            assertEquals(5, buffer.readableBytes());
            SPacketRemoveEntityEffect decoded = new SPacketRemoveEntityEffect();
            decoded.readPacketData(buffer);

            assertSame(widePotion, decoded.getPotion());
            assertEquals(0, buffer.readableBytes());
        }
        finally
        {
            buffer.release();
        }
    }

    @Test
    public void blockActionPacketRetainsWideBlockId() throws Exception
    {
        BlockPos pos = new BlockPos(12, 70, -8);
        SPacketBlockAction original = new SPacketBlockAction(pos, wideBlock, 4, 7);
        PacketBuffer buffer = new PacketBuffer(Unpooled.buffer());
        try
        {
            original.writePacketData(buffer);
            SPacketBlockAction decoded = new SPacketBlockAction();
            decoded.readPacketData(buffer);

            assertEquals(pos, decoded.getBlockPosition());
            assertEquals(4, decoded.getData1());
            assertEquals(7, decoded.getData2());
            assertSame(wideBlock, decoded.getBlockType());
            assertEquals(0, buffer.readableBytes());
        }
        finally
        {
            buffer.release();
        }
    }

    private static class TestPotion extends Potion
    {
        private TestPotion(ResourceLocation name)
        {
            super(false, 0);
            setRegistryName(name);
        }
    }

    private static class TestBlock extends Block
    {
        private TestBlock(ResourceLocation name)
        {
            super(Material.ROCK);
            setRegistryName(name);
        }
    }
}
