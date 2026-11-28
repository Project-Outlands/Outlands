package net.minecraftforge.registries;

import io.netty.buffer.Unpooled;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.util.PacketUtil;
import net.minecraftforge.fml.common.registry.ForgeTestRunner;
import net.minecraftforge.fml.common.registry.MinecraftTestHarness;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

@RunWith(ForgeTestRunner.class)
@ForgeTestRunner.Isolated
public class ReidItemNetworkTest
{
    private static final int WIDE_ITEM_ID = 32000;
    private static final int COUNT = 17;
    private static final int METADATA = 1234;

    private static Item wideItem;

    @BeforeClass
    public static void bootstrap()
    {
        MinecraftTestHarness.bootstrap();
        ForgeRegistry<Item> registry = RegistryManager.ACTIVE.getRegistry(GameData.ITEMS);
        wideItem = new ShareTagItem();
        wideItem.setRegistryName(new ResourceLocation("test", "wide_network_item"));
        registry.add(WIDE_ITEM_ID, wideItem);
        GameData.freezeData();
    }

    @Test
    public void serverNetworkStackUsesVarIntIdAndShareTag() throws Exception
    {
        NBTTagCompound fullTag = createFullTag();
        NBTTagCompound shareTag = new NBTTagCompound();
        shareTag.setString("shared", "value");
        PacketBuffer buffer = new PacketBuffer(Unpooled.buffer());
        ItemStack stack = new ItemStack(wideItem, COUNT, METADATA);
        stack.setTagCompound(fullTag);
        try
        {
            buffer.writeItemStack(stack);

            assertWideStackLayout(buffer, shareTag);
            ItemStack decoded = buffer.readItemStack();
            assertWideStack(decoded, shareTag);
        }
        finally
        {
            buffer.release();
        }
    }

    @Test
    public void clientNetworkStackUsesMatchingLayoutAndFullTag() throws Exception
    {
        NBTTagCompound fullTag = createFullTag();
        ItemStack stack = new ItemStack(wideItem, COUNT, METADATA);
        stack.setTagCompound(fullTag);
        PacketBuffer buffer = new PacketBuffer(Unpooled.buffer());
        try
        {
            PacketUtil.writeItemStackFromClientToServer(buffer, stack);

            assertWideStackLayout(buffer, fullTag);
            ItemStack decoded = buffer.readItemStack();
            assertWideStack(decoded, fullTag);
        }
        finally
        {
            buffer.release();
        }
    }

    @Test
    public void emptyStackUsesVarIntMarker() throws Exception
    {
        assertEmptyStackEncoding(false);
        assertEmptyStackEncoding(true);
    }

    private static NBTTagCompound createFullTag()
    {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("shared", "value");
        tag.setString("clientOnly", "full value");
        return tag;
    }

    private static void assertWideStackLayout(PacketBuffer buffer, NBTTagCompound expectedTag) throws Exception
    {
        assertEquals(WIDE_ITEM_ID, buffer.readVarInt());
        assertEquals(COUNT, buffer.readByte());
        assertEquals(METADATA, buffer.readShort());
        assertEquals(expectedTag, buffer.readCompoundTag());
        assertEquals(0, buffer.readableBytes());
        buffer.readerIndex(0);
    }

    private static void assertWideStack(ItemStack stack, NBTTagCompound expectedTag)
    {
        assertSame(wideItem, stack.getItem());
        assertEquals(COUNT, stack.getCount());
        assertEquals(METADATA, stack.getMetadata());
        assertEquals(expectedTag, stack.getTagCompound());
    }

    private static void assertEmptyStackEncoding(boolean clientToServer) throws Exception
    {
        PacketBuffer buffer = new PacketBuffer(Unpooled.buffer());
        try
        {
            if (clientToServer)
            {
                PacketUtil.writeItemStackFromClientToServer(buffer, ItemStack.EMPTY);
            }
            else
            {
                buffer.writeItemStack(ItemStack.EMPTY);
            }

            assertEquals(5, buffer.readableBytes());
            assertEquals(-1, buffer.readVarInt());
            assertEquals(0, buffer.readableBytes());
            buffer.readerIndex(0);
            assertSame(ItemStack.EMPTY, buffer.readItemStack());
        }
        finally
        {
            buffer.release();
        }
    }

    private static class ShareTagItem extends Item
    {
        @Override
        public NBTTagCompound getNBTShareTag(ItemStack stack)
        {
            NBTTagCompound tag = new NBTTagCompound();
            tag.setString("shared", stack.getTagCompound().getString("shared"));
            return tag;
        }
    }
}
