package net.minecraftforge.oredict;

import java.util.Map;

import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.registry.ForgeTestRunner;
import net.minecraftforge.fml.common.registry.MinecraftTestHarness;
import net.minecraftforge.registries.ForgeRegistry;
import net.minecraftforge.registries.GameData;
import net.minecraftforge.registries.RegistryManager;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(ForgeTestRunner.class)
@ForgeTestRunner.Isolated
public class OreDictionaryTest
{
    private static final int WIDE_ITEM_ID = 65537;
    private static final String EXACT_NAME = "test_runtime_wide_exact";
    private static final String OTHER_META_NAME = "test_runtime_wide_other_meta";
    private static final String WILDCARD_NAME = "test_runtime_wide_wildcard";
    private static final String STONE_NAME = "test_runtime_legacy_stone";

    private static Item wideItem;

    @BeforeClass
    public static void bootstrap()
    {
        MinecraftTestHarness.bootstrap();
        wideItem = new Item().setHasSubtypes(true).setRegistryName(new ResourceLocation("test", "wide_ore_item"));
        ForgeRegistry<Item> registry = RegistryManager.ACTIVE.getRegistry(GameData.ITEMS);
        registry.register(wideItem);
        Map<ResourceLocation, ForgeRegistry.Snapshot> snapshots = RegistryManager.ACTIVE.takeSnapshot(false);
        snapshots.get(GameData.ITEMS).ids.put(wideItem.getRegistryName(), WIDE_ITEM_ID);
        GameData.injectSnapshot(snapshots, false, true);
        GameData.freezeData();
    }

    @Test
    public void wideRegistrationsKeepIdsMetadataAndWildcardSeparate()
    {
        registerEntries();

        assertWideIds();
        int[] stoneIds = OreDictionary.getOreIDs(new ItemStack(Blocks.STONE));
        assertTrue(contains(stoneIds, OreDictionary.getOreID(STONE_NAME)));
        assertFalse(contains(stoneIds, OreDictionary.getOreID(EXACT_NAME)));
        assertFalse(contains(stoneIds, OreDictionary.getOreID(WILDCARD_NAME)));
    }

    @Test
    public void rebakeRetainsWideEntriesWithoutDuplicates()
    {
        registerEntries();
        OreDictionary.registerOre(EXACT_NAME, new ItemStack(wideItem, 1, 0));

        assertEquals(1, OreDictionary.getOres(EXACT_NAME).size());
        OreDictionary.rebakeMap();
        assertWideIds();
        assertEquals(1, OreDictionary.getOres(EXACT_NAME).size());
        int[] stoneIds = OreDictionary.getOreIDs(new ItemStack(Blocks.STONE));
        assertTrue(contains(stoneIds, OreDictionary.getOreID(STONE_NAME)));
        assertFalse(contains(stoneIds, OreDictionary.getOreID(EXACT_NAME)));
    }

    private static void registerEntries()
    {
        OreDictionary.registerOre(EXACT_NAME, new ItemStack(wideItem, 1, 0));
        OreDictionary.registerOre(OTHER_META_NAME, new ItemStack(wideItem, 1, 1));
        OreDictionary.registerOre(WILDCARD_NAME, new ItemStack(wideItem, 1, OreDictionary.WILDCARD_VALUE));
        OreDictionary.registerOre(STONE_NAME, new ItemStack(Blocks.STONE));
    }

    private static void assertWideIds()
    {
        int exact = OreDictionary.getOreID(EXACT_NAME);
        int otherMeta = OreDictionary.getOreID(OTHER_META_NAME);
        int wildcard = OreDictionary.getOreID(WILDCARD_NAME);
        int legacyStone = OreDictionary.getOreID(STONE_NAME);
        int[] firstIds = OreDictionary.getOreIDs(new ItemStack(wideItem, 1, 0));
        int[] secondIds = OreDictionary.getOreIDs(new ItemStack(wideItem, 1, 1));

        assertEquals(2, firstIds.length);
        assertTrue(contains(firstIds, exact));
        assertTrue(contains(firstIds, wildcard));
        assertFalse(contains(firstIds, otherMeta));
        assertFalse(contains(firstIds, legacyStone));
        assertEquals(2, secondIds.length);
        assertTrue(contains(secondIds, otherMeta));
        assertTrue(contains(secondIds, wildcard));
        assertFalse(contains(secondIds, exact));
        assertFalse(contains(secondIds, legacyStone));
    }

    private static boolean contains(int[] values, int expected)
    {
        for (int value : values)
        {
            if (value == expected)
            {
                return true;
            }
        }
        return false;
    }
}
