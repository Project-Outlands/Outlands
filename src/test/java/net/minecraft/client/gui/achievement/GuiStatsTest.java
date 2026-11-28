package net.minecraft.client.gui.achievement;

import java.util.Map;

import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.stats.StatCrafting;
import net.minecraft.stats.StatList;
import net.minecraft.stats.StatisticsManager;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.registry.ForgeTestRunner;
import net.minecraftforge.fml.common.registry.MinecraftTestHarness;
import net.minecraftforge.registries.ForgeRegistry;
import net.minecraftforge.registries.GameData;
import net.minecraftforge.registries.RegistryManager;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertTrue;

@RunWith(ForgeTestRunner.class)
@ForgeTestRunner.Isolated
public class GuiStatsTest
{
    private static final int WIDE_ITEM_ID = 40000;
    private static Item wideItem;

    @BeforeClass
    public static void bootstrap()
    {
        MinecraftTestHarness.bootstrap();
        wideItem = new Item().setRegistryName(new ResourceLocation("test", "wide_gui_stats_item"));
        ForgeRegistry<Item> items = RegistryManager.ACTIVE.getRegistry(GameData.ITEMS);
        items.register(wideItem);
        Map<ResourceLocation, ForgeRegistry.Snapshot> snapshots = RegistryManager.ACTIVE.takeSnapshot(false);
        snapshots.get(GameData.ITEMS).ids.put(wideItem.getRegistryName(), WIDE_ITEM_ID);
        GameData.injectSnapshot(snapshots, false, true);
        GameData.freezeData();
    }

    @Test
    public void statCountsAreComparedWithoutOverflow()
    {
        StatisticsManager manager = new StatisticsManager();
        GuiStats gui = new GuiStats(null, manager);
        GuiStats.StatsItem itemStats = gui.new StatsItem(null);
        GuiStats.StatsBlock blockStats = gui.new StatsBlock(null);
        StatCrafting low = (StatCrafting)StatList.getObjectUseStats(Items.STICK);
        StatCrafting high = (StatCrafting)StatList.getObjectUseStats(Items.APPLE);
        manager.unlockAchievement(null, low, Integer.MIN_VALUE);
        manager.unlockAchievement(null, high, Integer.MAX_VALUE);
        itemStats.sortColumn = 2;
        itemStats.sortOrder = 1;
        blockStats.sortColumn = 1;
        blockStats.sortOrder = 1;

        assertTrue(itemStats.statSorter.compare(low, high) < 0);
        assertTrue(itemStats.statSorter.compare(high, low) > 0);
        assertTrue(blockStats.statSorter.compare(low, high) < 0);
        assertTrue(blockStats.statSorter.compare(high, low) > 0);
    }

    @Test
    public void wideItemIdsRetainNumericOrderInBothComparators()
    {
        StatList.reinit();
        GuiStats gui = new GuiStats(null, new StatisticsManager());
        GuiStats.StatsItem itemStats = gui.new StatsItem(null);
        GuiStats.StatsBlock blockStats = gui.new StatsBlock(null);
        StatCrafting low = (StatCrafting)StatList.getObjectUseStats(Items.STICK);
        StatCrafting wide = (StatCrafting)StatList.getObjectUseStats(wideItem);
        itemStats.sortColumn = -1;
        blockStats.sortColumn = -1;

        assertTrue(itemStats.statSorter.compare(low, wide) < 0);
        assertTrue(itemStats.statSorter.compare(wide, low) > 0);
        assertTrue(blockStats.statSorter.compare(low, wide) < 0);
        assertTrue(blockStats.statSorter.compare(wide, low) > 0);
    }
}
