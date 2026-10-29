package net.minecraftforge.registries;

import net.minecraft.util.ResourceLocation;
import net.minecraft.world.biome.Biome;

public final class BiomeError
{
    private static final Biome INSTANCE = new ErrorBiome();

    private BiomeError()
    {
    }

    static void register()
    {
        ForgeRegistry<Biome> registry = RegistryManager.ACTIVE.getRegistry(GameData.BIOMES);
        registry.add(-1, INSTANCE, "reid");
    }

    /** Returns the current registry ID exposed to callers of the legacy byte biome API. */
    public static int getId()
    {
        return Biome.getIdForBiome(INSTANCE);
    }

    private static class ErrorBiome extends Biome
    {
        private ErrorBiome()
        {
            super(new BiomeProperties("Error"));
            setRegistryName(new ResourceLocation("reid", "error_biome"));
        }
    }
}
