package net.minecraft.stats;

import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.item.crafting.ShapelessRecipes;
import net.minecraft.util.NonNullList;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.TextComponentString;
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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

@RunWith(ForgeTestRunner.class)
@ForgeTestRunner.Isolated
public class StatListTest
{
    private static final int WIDE_BLOCK_ID = 5000;
    private static final int WIDE_BLOCK_ITEM_ID = 40000;
    private static final int WIDE_ITEM_ID = 40001;
    private static final int UNCRAFTABLE_ITEM_ID = 40002;

    private static Block wideBlock;
    private static Item wideBlockItem;
    private static Item wideItem;
    private static Item uncraftableItem;

    @BeforeClass
    public static void bootstrap()
    {
        MinecraftTestHarness.bootstrap();
        wideBlock = new Block(Material.ROCK).setRegistryName(new ResourceLocation("test", "wide_stats_block"));
        wideBlockItem = new ItemBlock(wideBlock).setRegistryName(new ResourceLocation("test", "wide_stats_block"));
        wideItem = new Item().setMaxDamage(16).setRegistryName(new ResourceLocation("test", "wide_stats_item"));
        uncraftableItem = new Item().setRegistryName(new ResourceLocation("test", "uncraftable_stats_item"));

        ForgeRegistry<Block> blocks = RegistryManager.ACTIVE.getRegistry(GameData.BLOCKS);
        ForgeRegistry<Item> items = RegistryManager.ACTIVE.getRegistry(GameData.ITEMS);
        blocks.register(wideBlock);
        items.register(wideBlockItem);
        items.register(wideItem);
        items.register(uncraftableItem);
        registerRecipe("wide_stats_block", wideBlockItem);
        registerRecipe("wide_stats_item", wideItem);

        Map<ResourceLocation, ForgeRegistry.Snapshot> snapshots = RegistryManager.ACTIVE.takeSnapshot(false);
        snapshots.get(GameData.BLOCKS).ids.put(wideBlock.getRegistryName(), WIDE_BLOCK_ID);
        snapshots.get(GameData.ITEMS).ids.put(wideBlockItem.getRegistryName(), WIDE_BLOCK_ITEM_ID);
        snapshots.get(GameData.ITEMS).ids.put(wideItem.getRegistryName(), WIDE_ITEM_ID);
        snapshots.get(GameData.ITEMS).ids.put(uncraftableItem.getRegistryName(), UNCRAFTABLE_ITEM_ID);
        GameData.injectSnapshot(snapshots, false, true);
        GameData.freezeData();
    }

    @Test
    public void wideBlockAndItemStatsSurviveReinit()
    {
        StatList.reinit();

        assertEquals(WIDE_BLOCK_ID, Block.getIdFromBlock(wideBlock));
        assertEquals(WIDE_BLOCK_ITEM_ID, Item.getIdFromItem(wideBlockItem));
        assertEquals(WIDE_ITEM_ID, Item.getIdFromItem(wideItem));
        StatBase mined = StatList.getBlockStats(wideBlock);
        assertNotNull(mined);
        assertTrue(StatList.MINE_BLOCK_STATS.contains((StatCrafting)mined));
        assertSame(mined, StatList.getOneShotStat(mined.statId));
        assertItemStats(wideBlockItem, false);
        assertItemStats(wideItem, true);
        assertTrue(StatList.USE_ITEM_STATS.contains((StatCrafting)StatList.getObjectUseStats(wideItem)));
        assertFalse(StatList.USE_ITEM_STATS.contains((StatCrafting)StatList.getObjectUseStats(wideBlockItem)));
    }

    @Test
    public void emptyAndMergedStatsKeepOriginalBehavior()
    {
        StatList.reinit();

        assertNull(StatList.getBlockStats(Blocks.AIR));
        assertNull(StatList.getCraftStats(uncraftableItem));
        assertNull(StatList.getObjectBreakStats(uncraftableItem));
        assertSame(StatList.getBlockStats(Blocks.GRASS), StatList.getBlockStats(Blocks.DIRT));
        assertSame(
                StatList.getObjectUseStats(Item.getItemFromBlock(Blocks.GRASS)),
                StatList.getObjectUseStats(Item.getItemFromBlock(Blocks.DIRT)));
    }

    @Test
    public void reinitPreservesUnknownStatsAndRemovesStaleCraftingStats()
    {
        StatList.reinit();
        StatBase unknown = new StatBasic("stat.testUnknownWideIds", new TextComponentString("unknown")).registerStat();
        StatBase previous = StatList.getObjectUseStats(wideItem);
        assertNotNull(previous);

        StatList.reinit();

        assertSame(unknown, StatList.getOneShotStat(unknown.statId));
        assertTrue(StatList.BASIC_STATS.contains(unknown));
        StatBase rebuilt = StatList.getObjectUseStats(wideItem);
        assertNotNull(rebuilt);
        assertNotSame(previous, rebuilt);
        assertSame(rebuilt, StatList.getOneShotStat(previous.statId));
        for (StatBase stat : StatList.ALL_STATS)
        {
            assertNotSame(previous, stat);
        }
    }

    private static void registerRecipe(String name, Item output)
    {
        ResourceLocation registryName = new ResourceLocation("test", name);
        ShapelessRecipes recipe = new ShapelessRecipes("", new ItemStack(output), NonNullList.create());
        recipe.setRegistryName(registryName);
        ForgeRegistry<IRecipe> recipes = RegistryManager.ACTIVE.getRegistry(GameData.RECIPES);
        recipes.register(recipe);
    }

    private static void assertItemStats(Item item, boolean damageable)
    {
        assertNotNull(StatList.getCraftStats(item));
        assertNotNull(StatList.getObjectUseStats(item));
        assertNotNull(StatList.getObjectsPickedUpStats(item));
        assertNotNull(StatList.getDroppedObjectStats(item));
        if (damageable)
        {
            assertNotNull(StatList.getObjectBreakStats(item));
        }
        else
        {
            assertNull(StatList.getObjectBreakStats(item));
        }
    }
}
