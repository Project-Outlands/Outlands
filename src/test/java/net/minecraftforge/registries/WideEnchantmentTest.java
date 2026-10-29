package net.minecraftforge.registries;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.command.CommandEnchant;
import net.minecraft.command.ICommandSender;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentData;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.EnumEnchantmentType;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.EnumCreatureAttribute;
import net.minecraft.init.Items;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.ItemEnchantedBook;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.EnumHandSide;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.World;
import net.minecraftforge.common.command.SelectorHandler;
import net.minecraftforge.common.command.SelectorHandlerManager;
import net.minecraftforge.fml.common.registry.ForgeTestRunner;
import net.minecraftforge.fml.common.registry.MinecraftTestHarness;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(ForgeTestRunner.class)
@ForgeTestRunner.Isolated
public class WideEnchantmentTest
{
    private static final String SELECTOR = "@wide_enchantment_test";
    private static final int FIRST_ID = 65536;
    private static final int SECOND_ID = 70002;

    private static Enchantment first;
    private static Enchantment second;

    @BeforeClass
    public static void bootstrap()
    {
        MinecraftTestHarness.bootstrap();
        ForgeRegistry<Enchantment> registry = RegistryManager.ACTIVE.getRegistry(GameData.ENCHANTMENTS);
        first = new TestEnchantment(new ResourceLocation("test", "wide_first"));
        second = new TestEnchantment(new ResourceLocation("test", "wide_second"));
        registry.add(FIRST_ID, first);
        registry.add(SECOND_ID, second);
        GameData.freezeData();
        SelectorHandlerManager.register(SELECTOR, new SelectorHandler()
        {
            @Override
            public <T extends Entity> List<T> matchEntities(ICommandSender sender, String token, Class<? extends T> targetClass)
            {
                Entity entity = sender.getCommandSenderEntity();
                return SELECTOR.equals(token) && entity != null && targetClass.isInstance(entity)
                        ? Collections.singletonList(targetClass.cast(entity))
                        : Collections.emptyList();
            }

            @Override
            public boolean matchesMultiplePlayers(String selectorStr)
            {
                return false;
            }

            @Override
            public boolean isSelector(String selectorStr)
            {
                return SELECTOR.equals(selectorStr);
            }
        });
    }

    @Test
    public void itemStackPreservesWideEnchantmentsAcrossDataPaths()
    {
        ItemStack stack = new ItemStack(Items.IRON_SWORD);
        stack.addEnchantment(first, 4);
        stack.addEnchantment(second, 9);

        NBTTagList enchantments = stack.getEnchantmentTagList();
        assertEquals(2, enchantments.tagCount());
        assertEnchantment(enchantments.getCompoundTagAt(0), FIRST_ID, 4);
        assertEnchantment(enchantments.getCompoundTagAt(1), SECOND_ID, 9);
        assertEquals(4, EnchantmentHelper.getEnchantmentLevel(first, stack));
        assertEquals(9, EnchantmentHelper.getEnchantmentLevel(second, stack));

        Map<Enchantment, Integer> decoded = EnchantmentHelper.getEnchantments(stack);
        assertEquals(Integer.valueOf(4), decoded.get(first));
        assertEquals(Integer.valueOf(9), decoded.get(second));
        assertEquals(13.0F, EnchantmentHelper.getModifierForCreature(stack, EnumCreatureAttribute.UNDEFINED), 0.0F);

        List<String> tooltip = stack.getTooltip(null, ITooltipFlag.TooltipFlags.NORMAL);
        assertTrue(tooltip.contains(first.getTranslatedName(4)));
        assertTrue(tooltip.contains(second.getTranslatedName(9)));
    }

    @Test
    public void helperWritesWideEnchantmentIdsWithoutChangingLevels()
    {
        Map<Enchantment, Integer> enchantments = new LinkedHashMap<>();
        enchantments.put(first, 3);
        enchantments.put(second, 8);
        ItemStack stack = new ItemStack(Items.IRON_SWORD);

        EnchantmentHelper.setEnchantments(enchantments, stack);

        NBTTagList encoded = stack.getEnchantmentTagList();
        assertEquals(2, encoded.tagCount());
        assertEnchantment(encoded.getCompoundTagAt(0), FIRST_ID, 3);
        assertEnchantment(encoded.getCompoundTagAt(1), SECOND_ID, 8);
        assertEquals(enchantments, EnchantmentHelper.getEnchantments(stack));
    }

    @Test
    public void enchantedBookReadsAndUpdatesWideEnchantmentIds()
    {
        ItemStack book = new ItemStack(Items.ENCHANTED_BOOK);
        NBTTagList existing = new NBTTagList();
        existing.appendTag(intEnchantment(FIRST_ID, 2));
        book.setTagInfo("StoredEnchantments", existing);

        ItemEnchantedBook.addEnchantment(book, new EnchantmentData(second, 5));
        ItemEnchantedBook.addEnchantment(book, new EnchantmentData(first, 7));

        NBTTagList stored = ItemEnchantedBook.getEnchantments(book);
        assertEquals(2, stored.tagCount());
        assertEnchantment(stored.getCompoundTagAt(0), FIRST_ID, 7);
        assertEnchantment(stored.getCompoundTagAt(1), SECOND_ID, 5);
        Map<Enchantment, Integer> decoded = EnchantmentHelper.getEnchantments(book);
        assertEquals(Integer.valueOf(7), decoded.get(first));
        assertEquals(Integer.valueOf(5), decoded.get(second));

        List<String> tooltip = new ArrayList<>();
        Items.ENCHANTED_BOOK.addInformation(book, null, tooltip, ITooltipFlag.TooltipFlags.NORMAL);
        assertTrue(tooltip.contains(first.getTranslatedName(7)));
        assertTrue(tooltip.contains(second.getTranslatedName(5)));
    }

    @Test
    public void commandChecksCompatibilityAgainstWideEnchantmentIds() throws Exception
    {
        ItemStack stack = new ItemStack(Items.IRON_SWORD);
        NBTTagList existing = new NBTTagList();
        existing.appendTag(intEnchantment(FIRST_ID, 6));
        stack.setTagInfo("ench", existing);
        TestEntity entity = new TestEntity(stack);

        new CommandEnchant().execute(null, new TestSender(entity), new String[] {SELECTOR, Integer.toString(SECOND_ID), "3"});

        NBTTagList enchantments = stack.getEnchantmentTagList();
        assertEquals(2, enchantments.tagCount());
        assertEnchantment(enchantments.getCompoundTagAt(0), FIRST_ID, 6);
        assertEnchantment(enchantments.getCompoundTagAt(1), SECOND_ID, 3);
    }

    private static NBTTagCompound intEnchantment(int id, int level)
    {
        NBTTagCompound compound = new NBTTagCompound();
        compound.setInteger("id", id);
        compound.setShort("lvl", (short)level);
        return compound;
    }

    private static void assertEnchantment(NBTTagCompound compound, int id, int level)
    {
        assertEquals(3, compound.getTagId("id"));
        assertEquals(id, compound.getInteger("id"));
        assertEquals(2, compound.getTagId("lvl"));
        assertEquals(level, compound.getShort("lvl"));
    }

    private static class TestEnchantment extends Enchantment
    {
        private TestEnchantment(ResourceLocation name)
        {
            super(Rarity.COMMON, EnumEnchantmentType.ALL, EntityEquipmentSlot.values());
            setRegistryName(name);
            setName(name.getPath());
        }

        @Override
        public int getMaxLevel()
        {
            return 10;
        }

        @Override
        public boolean canApply(ItemStack stack)
        {
            return true;
        }

        @Override
        public float calcDamageByCreature(int level, EnumCreatureAttribute creatureType)
        {
            return level;
        }

        @Override
        protected boolean canApplyTogether(Enchantment other)
        {
            return other != Enchantment.getEnchantmentByID(0);
        }
    }

    private static class TestEntity extends EntityLivingBase
    {
        private ItemStack mainHand;

        private TestEntity(ItemStack mainHand)
        {
            super(null);
            this.mainHand = mainHand;
        }

        @Override
        public Iterable<ItemStack> getArmorInventoryList()
        {
            return Collections.emptyList();
        }

        @Override
        public ItemStack getItemStackFromSlot(EntityEquipmentSlot slot)
        {
            return slot == EntityEquipmentSlot.MAINHAND ? this.mainHand : ItemStack.EMPTY;
        }

        @Override
        public void setItemStackToSlot(EntityEquipmentSlot slot, ItemStack stack)
        {
            if (slot == EntityEquipmentSlot.MAINHAND)
            {
                this.mainHand = stack;
            }
        }

        @Override
        public EnumHandSide getPrimaryHand()
        {
            return EnumHandSide.RIGHT;
        }
    }

    private static class TestSender implements ICommandSender
    {
        private final EntityLivingBase entity;

        private TestSender(EntityLivingBase entity)
        {
            this.entity = entity;
        }

        @Override
        public String getName()
        {
            return "wide_enchantment_test";
        }

        @Override
        public boolean canUseCommand(int permLevel, String commandName)
        {
            return true;
        }

        @Override
        public World getEntityWorld()
        {
            return null;
        }

        @Override
        public Entity getCommandSenderEntity()
        {
            return this.entity;
        }

        @Override
        public MinecraftServer getServer()
        {
            return null;
        }
    }
}
