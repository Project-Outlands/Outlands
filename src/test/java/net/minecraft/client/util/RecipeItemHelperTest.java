package net.minecraft.client.util;

import java.util.Map;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.Ingredient;
import net.minecraft.item.crafting.ShapelessRecipes;
import net.minecraft.util.NonNullList;
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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

@RunWith(ForgeTestRunner.class)
@ForgeTestRunner.Isolated
public class RecipeItemHelperTest
{
    private static final int FIRST_ITEM_ID = 65536;
    private static final int SECOND_ITEM_ID = 131072;

    private static Item firstItem;
    private static Item secondItem;

    @BeforeClass
    public static void bootstrap()
    {
        MinecraftTestHarness.bootstrap();
        firstItem = new Item().setHasSubtypes(true).setRegistryName(new ResourceLocation("test", "first_recipe_item"));
        secondItem = new Item().setHasSubtypes(true).setRegistryName(new ResourceLocation("test", "second_recipe_item"));
        ForgeRegistry<Item> registry = RegistryManager.ACTIVE.getRegistry(GameData.ITEMS);
        registry.register(firstItem);
        registry.register(secondItem);
        Map<ResourceLocation, ForgeRegistry.Snapshot> snapshots = RegistryManager.ACTIVE.takeSnapshot(false);
        snapshots.get(GameData.ITEMS).ids.put(firstItem.getRegistryName(), FIRST_ITEM_ID);
        snapshots.get(GameData.ITEMS).ids.put(secondItem.getRegistryName(), SECOND_ITEM_ID);
        GameData.injectSnapshot(snapshots, false, true);
        GameData.freezeData();
    }

    @Test
    public void wideRecipeDoesNotAcceptCollidingItem()
    {
        RecipeItemHelper helper = new RecipeItemHelper();
        ItemStack accounted = new ItemStack(firstItem, 1, 7);
        ItemStack required = new ItemStack(secondItem, 1, 7);
        ShapelessRecipes recipe = recipe(required);

        helper.accountStack(accounted);
        assertFalse(helper.canCraft(recipe, (IntList)null));

        helper.accountStack(required);
        assertTrue(helper.canCraft(recipe, (IntList)null));
    }

    @Test
    public void wideRecipeKeepsFullMetadata()
    {
        RecipeItemHelper helper = new RecipeItemHelper();
        ItemStack accounted = new ItemStack(firstItem, 1, 65535);
        ItemStack required = new ItemStack(firstItem, 1, 1);

        helper.accountStack(accounted);
        assertFalse(helper.canCraft(recipe(required), (IntList)null));

        helper.accountStack(required);
        assertTrue(helper.canCraft(recipe(required), (IntList)null));
    }

    @Test
    public void wideLongKeysRoundTripAndTrackCountsSeparately()
    {
        RecipeItemHelper helper = new RecipeItemHelper();
        ItemStack firstStack = new ItemStack(firstItem, 2, 7);
        ItemStack secondStack = new ItemStack(secondItem, 3, 7);
        long firstKey = RecipeItemHelper.packLong(firstStack);
        long secondKey = RecipeItemHelper.packLong(secondStack);

        assertNotEquals(firstKey, secondKey);
        ItemStack unpacked = RecipeItemHelper.unpackLong(firstKey);
        assertSame(firstItem, unpacked.getItem());
        assertEquals(7, unpacked.getMetadata());

        helper.accountStack(firstStack);
        helper.accountStack(secondStack);
        assertTrue(helper.itemToCount.isEmpty());
        assertTrue(helper.containsItem(firstKey));
        assertTrue(helper.containsItem(secondKey));
        assertEquals(firstKey, helper.tryTake(firstKey, 1));
        assertEquals(firstKey, helper.tryTake(firstKey, 1));
        assertFalse(helper.containsItem(firstKey));
        assertTrue(helper.containsItem(secondKey));

        helper.clear();
        assertFalse(helper.containsItem(secondKey));
    }

    @Test
    public void legacyEntriesKeepIntCountingAndOutputBehavior()
    {
        RecipeItemHelper helper = new RecipeItemHelper();
        ItemStack stack = new ItemStack(Items.DYE, 3, 4);
        int key = RecipeItemHelper.pack(stack);

        helper.accountStack(stack);
        assertEquals(3, helper.itemToCount.get(key));
        assertTrue(helper.containsItem(key));
        assertEquals(key, helper.tryTake(key, 2));
        assertEquals(1, helper.itemToCount.get(key));
        ItemStack unpacked = RecipeItemHelper.unpack(key);
        assertSame(Items.DYE, unpacked.getItem());
        assertEquals(4, unpacked.getMetadata());

        helper.accountStack(new ItemStack(Items.DYE, 1, 4));
        IntList output = new IntArrayList();
        assertTrue(helper.canCraft(recipe(new ItemStack(Items.DYE, 1, 4)), output));
        assertEquals(1, output.size());
        assertEquals(key, output.getInt(0));
    }

    @Test
    public void legacyRecipeOutputIsPreservedForZeroMaxAmount()
    {
        RecipeItemHelper helper = new RecipeItemHelper();
        ItemStack required = new ItemStack(Items.DYE, 1, 4);
        int existingKey = RecipeItemHelper.pack(required);
        IntList output = new IntArrayList();
        output.add(existingKey);

        assertTrue(helper.canCraft(recipe(required), output, 0));
        assertEquals(1, output.size());
        assertEquals(existingKey, output.getInt(0));
    }

    @Test
    public void legacyRecipeOutputIsPreservedForNegativeMaxAmount()
    {
        RecipeItemHelper helper = new RecipeItemHelper();
        ItemStack required = new ItemStack(Items.DYE, 1, 4);
        int existingKey = RecipeItemHelper.pack(required);
        IntList output = new IntArrayList();
        output.add(existingKey);

        assertTrue(helper.canCraft(recipe(required), output, -1));
        assertEquals(1, output.size());
        assertEquals(existingKey, output.getInt(0));
    }

    @Test
    public void longRecipeEntriesReturnWideKeys()
    {
        RecipeItemHelper helper = new RecipeItemHelper();
        ItemStack required = new ItemStack(secondItem, 1, 9);
        ShapelessRecipes recipe = recipe(required);
        long key = RecipeItemHelper.packLong(required);
        helper.accountStack(new ItemStack(secondItem, 2, 9));

        LongList craftOutput = new LongArrayList();
        assertTrue(helper.canCraftLong(recipe, craftOutput));
        assertEquals(1, craftOutput.size());
        assertEquals(key, craftOutput.getLong(0));
        assertTrue(helper.canCraftLong(recipe, null, 1));

        LongList biggestOutput = new LongArrayList();
        assertEquals(2, helper.getBiggestCraftableStackLong(recipe, biggestOutput));
        assertEquals(1, biggestOutput.size());
        assertEquals(key, biggestOutput.getLong(0));

        LongList boundedOutput = new LongArrayList();
        assertEquals(1, helper.getBiggestCraftableStackLong(recipe, 1, boundedOutput));
        assertEquals(1, boundedOutput.size());
        assertEquals(key, boundedOutput.getLong(0));
    }

    @Test
    public void legacyRecipeOutputRejectsWideKeys()
    {
        RecipeItemHelper helper = new RecipeItemHelper();
        ItemStack required = new ItemStack(secondItem, 1, 9);
        helper.accountStack(required);

        assertThrows(IllegalArgumentException.class, () -> helper.canCraft(recipe(required), new IntArrayList()));
    }

    @Test
    public void legacyPackRejectsUnrepresentableIds()
    {
        assertThrows(IllegalArgumentException.class, () -> RecipeItemHelper.pack(new ItemStack(firstItem, 1, 1)));
    }

    private static ShapelessRecipes recipe(ItemStack required)
    {
        NonNullList<Ingredient> ingredients = NonNullList.create();
        ingredients.add(Ingredient.fromStacks(required));
        return new ShapelessRecipes("", required, ingredients);
    }
}
