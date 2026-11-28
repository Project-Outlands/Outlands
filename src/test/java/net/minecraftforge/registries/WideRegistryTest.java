package net.minecraftforge.registries;

import java.util.HashMap;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.registry.ForgeTestRunner;
import net.minecraftforge.fml.common.registry.MinecraftTestHarness;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

@RunWith(ForgeTestRunner.class)
@ForgeTestRunner.Isolated
public class WideRegistryTest
{
    private static final ResourceLocation REGISTRY_NAME = new ResourceLocation("test", "blocks");

    @BeforeClass
    public static void bootstrap()
    {
        MinecraftTestHarness.bootstrap();
    }

    @Test
    public void wideBlocksAndItemBlocksRetainRegistryMappings()
    {
        ForgeRegistry<Block> blocks = RegistryManager.ACTIVE.getRegistry(GameData.BLOCKS);
        ForgeRegistry<Item> items = RegistryManager.ACTIVE.getRegistry(GameData.ITEMS);
        Block first = new TestBlock(new ResourceLocation("test", "wide_z_first_item_block"));
        Block second = new TestBlock(new ResourceLocation("test", "wide_z_second_item_block"));
        Item firstItem = new ItemBlock(first).setRegistryName(first.getRegistryName());
        Item secondItem = new ItemBlock(second).setRegistryName(second.getRegistryName());
        int firstId = 4096;
        int secondId = 65536;

        blocks.add(firstId, first);
        blocks.add(secondId, second);
        items.add(firstId, firstItem);
        items.add(secondId, secondItem);
        GameData.freezeData();

        assertTrue(firstId > 4095);
        assertTrue(secondId > 65535);
        assertEquals(firstId, Block.getIdFromBlock(first));
        assertEquals(secondId, Block.getIdFromBlock(second));
        assertEquals(firstId, Item.getIdFromItem(firstItem));
        assertEquals(secondId, Item.getIdFromItem(secondItem));
        assertSame(firstItem, Item.getItemById(firstId));
        assertSame(secondItem, Item.getItemById(secondId));
        assertEquals(256, Item.getIdFromItem(Items.IRON_SHOVEL));
        assertSame(firstItem, Item.getItemFromBlock(first));
        assertSame(secondItem, Item.getItemFromBlock(second));
        assertSame(first, Block.getBlockFromItem(firstItem));
        assertSame(second, Block.getBlockFromItem(secondItem));
        assertSame(second, blocks.getValue(secondId));
        assertSame(second, blocks.getValue(second.getRegistryName()));
        assertSame(second, Block.getBlockById(secondId));
        int secondStateId = secondId << 4;
        assertEquals(secondStateId, Block.getStateId(second.getDefaultState()));
        assertSame(second.getDefaultState(), Block.getStateById(secondStateId));
    }

    @Test
    public void explicitSnapshotIdsSurviveFreezeCopyAndSync()
    {
        RegistryManager activeManager = new RegistryManager("ACTIVE_TEST");
        ForgeRegistry<Block> active = createRegistry(activeManager);
        ResourceLocation firstName = new ResourceLocation("test", "snapshot_first");
        ResourceLocation secondName = new ResourceLocation("test", "snapshot_second");
        Block first = new TestBlock(firstName);
        Block second = new TestBlock(secondName);
        active.register(first);
        active.register(second);

        int firstId = 70001;
        int secondId = 65537;
        ForgeRegistry.Snapshot snapshot = active.makeSnapshot();
        snapshot.ids.put(firstName, firstId);
        snapshot.ids.put(secondName, secondId);
        ForgeRegistry.Snapshot restored = ForgeRegistry.Snapshot.read(snapshot.write());

        RegistryManager stagingManager = new RegistryManager("STAGING_TEST");
        ForgeRegistry<Block> staging = stagingManager.getRegistry(REGISTRY_NAME, activeManager);
        staging.loadIds(restored.ids, restored.overrides, new HashMap<>(), new HashMap<>(), active, REGISTRY_NAME);
        staging.freeze();

        assertEquals(firstId, staging.getID(firstName));
        assertEquals(secondId, staging.getID(secondName));

        RegistryManager syncedManager = new RegistryManager("SYNCED_TEST");
        ForgeRegistry<Block> synced = syncedManager.getRegistry(REGISTRY_NAME, stagingManager);
        synced.sync(REGISTRY_NAME, staging);
        synced.freeze();
        assertEquals(firstId, synced.getID(firstName));
        assertEquals(secondId, synced.getID(secondName));

        ForgeRegistry<Block> copied = staging.copy(new RegistryManager("COPIED_TEST"));
        copied.add(firstId, first);
        copied.add(secondId, second);
        copied.freeze();
        assertEquals(firstId, copied.getID(firstName));
        assertEquals(secondId, copied.getID(secondName));
    }

    private static ForgeRegistry<Block> createRegistry(RegistryManager manager)
    {
        return manager.createRegistry(REGISTRY_NAME, Block.class, null, 0, Integer.MAX_VALUE - 1,
                null, null, null, null, true, true, false, null, null);
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
