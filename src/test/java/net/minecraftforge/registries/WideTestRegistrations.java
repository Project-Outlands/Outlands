package net.minecraftforge.registries;

import net.minecraft.block.Block;
import net.minecraft.potion.Potion;

public final class WideTestRegistrations
{
    private WideTestRegistrations()
    {
    }

    public static void registerPotion(int id, Potion potion)
    {
        ForgeRegistry<Potion> registry = RegistryManager.ACTIVE.getRegistry(GameData.POTIONS);
        registry.add(id, potion);
    }

    public static void registerBlock(int id, Block block)
    {
        ForgeRegistry<Block> registry = RegistryManager.ACTIVE.getRegistry(GameData.BLOCKS);
        registry.add(id, block);
    }
}
