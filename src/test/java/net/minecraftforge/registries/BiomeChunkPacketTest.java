package net.minecraftforge.registries;

import io.netty.buffer.Unpooled;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.server.SPacketChunkData;
import net.minecraft.profiler.Profiler;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldProviderSurface;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.BiomeProvider;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.storage.WorldInfo;
import net.minecraftforge.fml.common.registry.ForgeTestRunner;
import net.minecraftforge.fml.common.registry.MinecraftTestHarness;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

@RunWith(ForgeTestRunner.class)
@ForgeTestRunner.Isolated
public class BiomeChunkPacketTest
{
    private static final int WIDE_ID = 300;
    private static Biome wideBiome;

    @BeforeClass
    public static void bootstrap()
    {
        MinecraftTestHarness.bootstrap();
        wideBiome = new TestBiome(new ResourceLocation("test", "wide_packet_biome"));
        ForgeRegistry<Biome> registry = RegistryManager.ACTIVE.getRegistry(GameData.BIOMES);
        registry.add(WIDE_ID, wideBiome);
        GameData.freezeData();
    }

    @Test
    public void fullChunkPacketRoundTripsAllWideBiomeIds() throws Exception
    {
        BiomeProvider provider = new FixedBiomeProvider(wideBiome);
        World world = new TestWorld(provider);
        Chunk source = new Chunk(world, 0, 0);
        fillBiomes(source, provider);
        SPacketChunkData packet = new SPacketChunkData(source, 65535);
        PacketBuffer wire = new PacketBuffer(Unpooled.buffer());
        try
        {
            packet.writePacketData(wire);
            SPacketChunkData decoded = new SPacketChunkData();
            decoded.readPacketData(wire);
            PacketBuffer chunkData = decoded.getReadBuffer();
            try
            {
                Chunk target = new Chunk(world, 0, 0);
                target.read(chunkData, decoded.getExtractedSize(), decoded.isFullChunk());

                assertEquals(0, wire.readableBytes());
                assertEquals(0, chunkData.readableBytes());
                for (int z = 0; z < 16; z++)
                {
                    for (int x = 0; x < 16; x++)
                    {
                        assertSame(wideBiome, target.getBiome(new BlockPos(x, 0, z), provider));
                    }
                }
            }
            finally
            {
                chunkData.release();
            }
        }
        finally
        {
            wire.release();
        }
    }

    private static void fillBiomes(Chunk chunk, BiomeProvider provider)
    {
        for (int z = 0; z < 16; z++)
        {
            for (int x = 0; x < 16; x++)
            {
                chunk.getBiome(new BlockPos(x, 0, z), provider);
            }
        }
    }

    private static class TestBiome extends Biome
    {
        private TestBiome(ResourceLocation registryName)
        {
            super(new BiomeProperties("Wide Packet"));
            setRegistryName(registryName);
        }
    }

    private static class FixedBiomeProvider extends BiomeProvider
    {
        private final Biome biome;

        private FixedBiomeProvider(Biome biome)
        {
            this.biome = biome;
        }

        @Override
        public Biome getBiome(BlockPos pos, Biome defaultBiome)
        {
            return this.biome;
        }
    }

    private static class TestWorld extends World
    {
        private TestWorld(BiomeProvider biomeProvider)
        {
            super(null, new WorldInfo(new NBTTagCompound()), new TestWorldProvider(biomeProvider), new Profiler(), false);
        }

        @Override
        protected IChunkProvider createChunkProvider()
        {
            return null;
        }

        @Override
        protected boolean isChunkLoaded(int x, int z, boolean allowEmpty)
        {
            return false;
        }
    }

    private static class TestWorldProvider extends WorldProviderSurface
    {
        private TestWorldProvider(BiomeProvider biomeProvider)
        {
            this.biomeProvider = biomeProvider;
        }
    }
}
