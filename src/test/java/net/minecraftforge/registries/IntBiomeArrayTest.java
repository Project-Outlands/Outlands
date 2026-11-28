package net.minecraftforge.registries;

import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.entity.EnumCreatureType;
import net.minecraft.init.Biomes;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.network.INetHandler;
import net.minecraft.network.NetworkManager;
import net.minecraft.profiler.Profiler;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.PlayerList;
import net.minecraft.util.IThreadListener;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.datafix.DataFixer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.EnumDifficulty;
import net.minecraft.world.GameType;
import net.minecraft.world.World;
import net.minecraft.world.WorldProviderSurface;
import net.minecraft.world.WorldServer;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.BiomeProvider;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.chunk.storage.AnvilChunkLoader;
import net.minecraft.world.chunk.storage.IChunkLoader;
import net.minecraft.world.chunk.storage.RegionFileCache;
import net.minecraft.world.gen.ChunkProviderServer;
import net.minecraft.world.gen.IChunkGenerator;
import net.minecraft.world.gen.layer.GenLayer;
import net.minecraft.world.gen.layer.GenLayerRiverMix;
import net.minecraft.world.gen.layer.GenLayerVoronoiZoom;
import net.minecraft.world.storage.ThreadedFileIOBase;
import net.minecraft.world.storage.WorldInfo;
import net.minecraftforge.common.util.CompoundDataFixer;
import net.minecraftforge.fml.relauncher.FMLLaunchHandler;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.IFMLSidedHandler;
import net.minecraftforge.fml.common.ModContainer;
import net.minecraftforge.fml.common.StartupQuery;
import net.minecraftforge.fml.common.eventhandler.EventBus;
import net.minecraftforge.fml.common.registry.ForgeTestRunner;
import net.minecraftforge.fml.common.registry.MinecraftTestHarness;
import net.minecraftforge.fml.relauncher.ReflectionHelper;
import net.minecraftforge.fml.relauncher.Side;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

@RunWith(ForgeTestRunner.class)
@ForgeTestRunner.Isolated
public class IntBiomeArrayTest
{
    private static final int LEGACY_ID = 200;
    private static final int WIDE_ID = 300;

    private static Biome legacyBiome;
    private static Biome wideBiome;
    private static File saveLocation;
    private static Map<ResourceLocation, ForgeRegistry.Snapshot> originalSnapshots;

    @BeforeClass
    public static void bootstrap() throws IOException
    {
        MinecraftTestHarness.bootstrap();
        saveLocation = Files.createTempDirectory("outlands-biome-nbt").toFile();
        originalSnapshots = snapshotRegistries();
        ForgeRegistry<Biome> registry = RegistryManager.ACTIVE.getRegistry(GameData.BIOMES);
        legacyBiome = new TestBiome("Legacy", new ResourceLocation("test", "legacy_biome"));
        wideBiome = new TestBiome("Wide", new ResourceLocation("test", "wide_biome"));
        registry.add(LEGACY_ID, legacyBiome);
        registry.add(WIDE_ID, wideBiome);
        GameData.freezeData();
        FMLLaunchHandler.setSide(Side.SERVER);
        TestSidedHandler sidedHandler = new TestSidedHandler();
        try
        {
            FMLCommonHandler.instance().beginLoading(sidedHandler);
        }
        catch (ReflectionHelper.UnableToAccessFieldException exception)
        {
            assertSame(sidedHandler, FMLCommonHandler.instance().getSidedDelegate());
        }
    }

    @AfterClass
    public static void tearDown() throws Exception
    {
        try
        {
            try
            {
                ThreadedFileIOBase.getThreadedIOInstance().shutdown();
            }
            finally
            {
                GameData.injectSnapshot(originalSnapshots, false, true);
            }
        }
        finally
        {
            RegionFileCache.clearRegionFileReferences();
            deleteRecursively(saveLocation);
        }
    }

    @Test
    public void chunkRetainsWideIdsAcrossRepeatedReads()
    {
        BiomeProvider provider = new FixedBiomeProvider(wideBiome);
        World world = new TestWorld(provider);
        Chunk chunk = new Chunk(world, 0, 0);
        BlockPos pos = new BlockPos(3, 0, 7);

        assertSame(wideBiome, chunk.getBiome(pos, provider));
        assertSame(wideBiome, chunk.getBiome(pos, provider));
    }

    @Test
    public void freshChunkStartsWithUnsetNarrowBiomes()
    {
        World world = new TestWorld(new FixedBiomeProvider(wideBiome));
        Chunk chunk = new Chunk(world, 0, 0);

        assertFalse(chunk.hasWideBiomes());
        assertTrue(chunk.hasUnsetBiomes());

        byte[] expected = new byte[256];
        Arrays.fill(expected, (byte) -1);
        assertArrayEquals(expected, chunk.getBiomeArray());
    }

    @Test
    public void byteArrayIsTheRealStorageWhileNarrow()
    {
        // The vanilla generator idiom: take the array, write into it, never call the setter again.
        BiomeProvider provider = new FixedBiomeProvider(Biomes.PLAINS);
        World world = new TestWorld(provider);
        Chunk chunk = new Chunk(world, 0, 0);

        chunk.getBiomeArray()[3 << 4 | 7] = (byte) LEGACY_ID;

        assertSame(legacyBiome, chunk.getBiome(new BlockPos(7, 0, 3), provider));
        assertFalse(chunk.hasWideBiomes());
    }

    @Test
    public void wideCellsReadAsUnsetInTheByteView()
    {
        BiomeProvider provider = new FixedBiomeProvider(Biomes.PLAINS);
        World world = new TestWorld(provider);
        Chunk chunk = new Chunk(world, 0, 0);
        int[] ids = new int[256];
        Arrays.fill(ids, LEGACY_ID);
        ids[3 << 4 | 7] = WIDE_ID;
        chunk.setBiomeIds(ids);

        assertTrue(chunk.hasWideBiomes());

        byte[] view = chunk.getBiomeArray();
        assertEquals((byte) LEGACY_ID, view[0]);
        assertEquals((byte) -1, view[3 << 4 | 7]);
        assertSame(wideBiome, chunk.getBiome(new BlockPos(7, 0, 3), provider));

        // A byte write reaches a representable cell and leaves the wide one alone, because 255 there means
        // "this view cannot see it" rather than "unset it".
        view[0] = (byte) 42;
        chunk.setBiomeArray(view);
        assertEquals(42, chunk.getBiomeIds()[0]);
        assertEquals(WIDE_ID, chunk.getBiomeIds()[3 << 4 | 7]);
    }

    @Test
    public void setBiomeIdsStaysNarrowWhileEveryIdFits()
    {
        World world = new TestWorld(new FixedBiomeProvider(Biomes.PLAINS));
        Chunk chunk = new Chunk(world, 0, 0);
        int[] ids = new int[256];
        Arrays.fill(ids, LEGACY_ID);
        chunk.setBiomeIds(ids);

        assertFalse(chunk.hasWideBiomes());
        assertEquals((byte) LEGACY_ID, chunk.getBiomeArray()[0]);
    }

    @Test
    public void anvilUpgradesLegacyByteBiomes()
    {
        byte[] encoded = new byte[256];
        Arrays.fill(encoded, (byte)LEGACY_ID);
        NBTTagCompound root = createChunkTag();
        root.getCompoundTag("Level").setByteArray("Biomes", encoded);
        BiomeProvider provider = new FixedBiomeProvider(Biomes.PLAINS);
        World world = new TestWorld(provider);

        Chunk chunk = new ReadableAnvilChunkLoader().read(world, root);

        assertSame(legacyBiome, chunk.getBiome(new BlockPos(4, 0, 9), provider));
    }

    @Test
    public void anvilReadsIntBiomes()
    {
        int[] encoded = new int[256];
        Arrays.fill(encoded, WIDE_ID);
        NBTTagCompound root = createChunkTag();
        root.getCompoundTag("Level").setIntArray("Biomes", encoded);
        BiomeProvider provider = new FixedBiomeProvider(Biomes.PLAINS);
        World world = new TestWorld(provider);

        Chunk chunk = new ReadableAnvilChunkLoader().read(world, root);

        assertSame(wideBiome, chunk.getBiome(new BlockPos(4, 0, 9), provider));
    }

    @Test
    public void anvilWritesLegacyByteBiomesWhileNarrow() throws Exception
    {
        BiomeProvider provider = new FixedBiomeProvider(Biomes.PLAINS);
        World world = new TestWorld(provider);
        Chunk chunk = new Chunk(world, 0, 0);
        Arrays.fill(chunk.getBiomeArray(), (byte) LEGACY_ID);
        AnvilChunkLoader loader = new AnvilChunkLoader(saveLocation, new DataFixer(1343));

        loader.saveChunk(world, chunk);
        loader.flush();

        NBTTagCompound saved;
        try (DataInputStream input = RegionFileCache.getChunkInputStream(saveLocation, 0, 0))
        {
            saved = CompressedStreamTools.read(input);
        }
        NBTTagCompound level = saved.getCompoundTag("Level");

        // Same tag type vanilla writes, so a world that never saw a wide biome stays readable elsewhere.
        assertTrue(level.hasKey("Biomes", 7));
        assertEquals((byte) LEGACY_ID, level.getByteArray("Biomes")[0]);

        Chunk reloaded = new ReadableAnvilChunkLoader().read(world, saved);
        assertFalse(reloaded.hasWideBiomes());
        assertSame(legacyBiome, reloaded.getBiome(new BlockPos(4, 0, 9), provider));
    }

    @Test
    public void anvilWritesIntBiomes() throws Exception
    {
        BiomeProvider provider = new FixedBiomeProvider(wideBiome);
        World world = new TestWorld(provider);
        Chunk chunk = new Chunk(world, 0, 0);
        fillBiomes(chunk, provider);
        AnvilChunkLoader loader = new AnvilChunkLoader(saveLocation, new DataFixer(1343));

        loader.saveChunk(world, chunk);
        loader.flush();

        NBTTagCompound saved;
        try (DataInputStream input = RegionFileCache.getChunkInputStream(saveLocation, 0, 0))
        {
            saved = CompressedStreamTools.read(input);
        }
        NBTTagCompound level = saved.getCompoundTag("Level");
        assertTrue(level.hasKey("Biomes", 11));
        int[] expected = new int[256];
        Arrays.fill(expected, WIDE_ID);
        assertArrayEquals(expected, level.getIntArray("Biomes"));
    }

    /**
     * A generated chunk has to enter the provider's loaded set: that is what makes the second call return the
     * same instance, what onLoad() marks, and what saveChunks() iterates. Losing it means the world has no
     * chunks at all - the terrain never reaches the client and nothing can be saved.
     */
    @Test
    public void generatedChunkIsRegisteredWithTheProvider()
    {
        TestWorldServer world = new TestWorldServer(new FixedBiomeProvider(legacyBiome));
        ChunkProviderServer chunkProvider = new ChunkProviderServer(world, new EmptyChunkLoader(), new TestChunkGenerator(world, false));

        Chunk first = chunkProvider.provideChunk(0, 0);
        Chunk second = chunkProvider.provideChunk(0, 0);

        assertSame(first, second);
        assertTrue(first.isLoaded());
    }

    @Test
    public void chunkProviderFillsUninitializedChunkWithWideBiomeIds()
    {
        TestWorldServer world = new TestWorldServer(new FixedBiomeProvider(wideBiome));
        TestChunkGenerator generator = new TestChunkGenerator(world, false);
        ChunkProviderServer chunkProvider = new ChunkProviderServer(world, new EmptyChunkLoader(), generator);

        Chunk chunk = chunkProvider.provideChunk(0, 0);

        int[] expected = new int[256];
        Arrays.fill(expected, WIDE_ID);
        assertArrayEquals(expected, chunk.getBiomeIds());
    }

    @Test
    public void chunkProviderKeepsInitializedModdedBiomeIds()
    {
        TestWorldServer world = new TestWorldServer(new FixedBiomeProvider(legacyBiome));
        TestChunkGenerator generator = new TestChunkGenerator(world, true);
        ChunkProviderServer chunkProvider = new ChunkProviderServer(world, new EmptyChunkLoader(), generator);

        Chunk chunk = chunkProvider.provideChunk(0, 0);

        int[] expected = new int[256];
        Arrays.fill(expected, WIDE_ID);
        assertArrayEquals(expected, chunk.getBiomeIds());
    }

    @Test
    public void riverMixRetainsWideRiverIds()
    {
        ResourceLocation riverName = new ResourceLocation("minecraft", "river");
        remapBiome(riverName, 301);
        int riverId = Biome.getIdForBiome(Biomes.RIVER);
        GenLayer biomes = new FixedLayer(1, Biome.getIdForBiome(Biomes.PLAINS));
        GenLayer rivers = new FixedLayer(2, riverId);

        int[] result = new GenLayerRiverMix(3, biomes, rivers).getInts(0, 0, 1, 1);

        assertEquals(riverId, result[0]);
    }

    @Test
    public void voronoiZoomRetainsWideBiomeIds()
    {
        GenLayer parent = new FixedLayer(1, WIDE_ID);
        int[] result = new GenLayerVoronoiZoom(2, parent).getInts(0, 0, 4, 4);
        int[] expected = new int[16];
        Arrays.fill(expected, WIDE_ID);

        assertArrayEquals(expected, Arrays.copyOf(result, expected.length));
    }

    static void fillBiomes(Chunk chunk, BiomeProvider provider)
    {
        for (int z = 0; z < 16; z++)
        {
            for (int x = 0; x < 16; x++)
            {
                chunk.getBiome(new BlockPos(x, 0, z), provider);
            }
        }
    }

    private static void remapBiome(ResourceLocation name, int id)
    {
        Map<ResourceLocation, ForgeRegistry.Snapshot> snapshots = snapshotRegistries();
        snapshots.get(GameData.BIOMES).ids.put(name, id);
        GameData.injectSnapshot(snapshots, false, true);
    }

    private static Map<ResourceLocation, ForgeRegistry.Snapshot> snapshotRegistries()
    {
        Map<ResourceLocation, ForgeRegistry.Snapshot> snapshots = new HashMap<>();
        RegistryManager.ACTIVE.registries.forEach((registryName, registry) -> snapshots.put(registryName, registry.makeSnapshot()));
        return snapshots;
    }

    private static void deleteRecursively(File file) throws IOException
    {
        if (file == null || !file.exists())
        {
            return;
        }
        if (file.isDirectory())
        {
            File[] children = file.listFiles();
            if (children == null)
            {
                throw new IOException("Could not list temporary test directory " + file);
            }
            for (File child : children)
            {
                deleteRecursively(child);
            }
        }
        Files.delete(file.toPath());
    }

    private static NBTTagCompound createChunkTag()
    {
        NBTTagCompound root = new NBTTagCompound();
        NBTTagCompound level = new NBTTagCompound();
        root.setTag("Level", level);
        level.setInteger("xPos", 0);
        level.setInteger("zPos", 0);
        level.setIntArray("HeightMap", new int[256]);
        level.setTag("Sections", new NBTTagList());
        return root;
    }

    private static class TestBiome extends Biome
    {
        private TestBiome(String name, ResourceLocation registryName)
        {
            super(new BiomeProperties(name));
            setRegistryName(registryName);
        }
    }

    static class FixedBiomeProvider extends BiomeProvider
    {
        private final Biome biome;

        FixedBiomeProvider(Biome biome)
        {
            this.biome = biome;
        }

        @Override
        public Biome getBiome(BlockPos pos, Biome defaultBiome)
        {
            return this.biome;
        }

        @Override
        public Biome[] getBiomes(Biome[] oldBiomeList, int x, int z, int width, int depth)
        {
            Biome[] biomes = oldBiomeList;
            if (biomes == null || biomes.length < width * depth)
            {
                biomes = new Biome[width * depth];
            }
            Arrays.fill(biomes, this.biome);
            return biomes;
        }
    }

    private static class TestWorldServer extends WorldServer
    {
        private final BiomeProvider biomeProvider;

        private TestWorldServer(BiomeProvider biomeProvider)
        {
            super(new TestServer(), null, new WorldInfo(new NBTTagCompound()), 0, new Profiler());
            this.biomeProvider = biomeProvider;
        }

        @Override
        protected IChunkProvider createChunkProvider()
        {
            return null;
        }

        @Override
        public BiomeProvider getBiomeProvider()
        {
            return this.biomeProvider;
        }
    }

    private static class TestServer extends MinecraftServer
    {
        private TestServer()
        {
            super(saveLocation, null, new DataFixer(1343), null, null, null, null);
            this.setPlayerList(new PlayerList(this) {});
        }

        @Override
        public boolean init()
        {
            return true;
        }

        @Override
        public boolean canStructuresSpawn()
        {
            return false;
        }

        @Override
        public GameType getGameType()
        {
            return GameType.SURVIVAL;
        }

        @Override
        public EnumDifficulty getDifficulty()
        {
            return EnumDifficulty.PEACEFUL;
        }

        @Override
        public boolean isHardcore()
        {
            return false;
        }

        @Override
        public int getOpPermissionLevel()
        {
            return 0;
        }

        @Override
        public boolean shouldBroadcastRconToOps()
        {
            return false;
        }

        @Override
        public boolean shouldBroadcastConsoleToOps()
        {
            return false;
        }

        @Override
        public boolean isDedicatedServer()
        {
            return true;
        }

        @Override
        public boolean shouldUseNativeTransport()
        {
            return false;
        }

        @Override
        public boolean isCommandBlockEnabled()
        {
            return false;
        }

        @Override
        public String shareToLAN(GameType type, boolean allowCheats)
        {
            return null;
        }
    }

    private static class EmptyChunkLoader implements IChunkLoader
    {
        @Override
        public Chunk loadChunk(World worldIn, int x, int z)
        {
            return null;
        }

        @Override
        public void saveChunk(World worldIn, Chunk chunkIn)
        {
        }

        @Override
        public void saveExtraChunkData(World worldIn, Chunk chunkIn)
        {
        }

        @Override
        public void chunkTick()
        {
        }

        @Override
        public void flush()
        {
        }

        @Override
        public boolean isChunkGeneratedAt(int x, int z)
        {
            return false;
        }
    }

    private static class TestChunkGenerator implements IChunkGenerator
    {
        private final World world;
        private final boolean initializeBiomes;

        private TestChunkGenerator(World world, boolean initializeBiomes)
        {
            this.world = world;
            this.initializeBiomes = initializeBiomes;
        }

        @Override
        public Chunk generateChunk(int x, int z)
        {
            Chunk chunk = new Chunk(this.world, x, z);
            if (this.initializeBiomes)
            {
                Arrays.fill(chunk.getBiomeIds(), WIDE_ID);
            }
            return chunk;
        }

        @Override
        public void populate(int x, int z)
        {
        }

        @Override
        public boolean generateStructures(Chunk chunkIn, int x, int z)
        {
            return false;
        }

        @Override
        public List<Biome.SpawnListEntry> getPossibleCreatures(EnumCreatureType creatureType, BlockPos pos)
        {
            return Collections.emptyList();
        }

        @Override
        public BlockPos getNearestStructurePos(World worldIn, String structureName, BlockPos position, boolean findUnexplored)
        {
            return null;
        }

        @Override
        public void recreateStructures(Chunk chunkIn, int x, int z)
        {
        }

        @Override
        public boolean isInsideStructure(World worldIn, String structureName, BlockPos pos)
        {
            return false;
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

        @Override
        public void checkSessionLock()
        {
        }
    }

    private static class TestWorldProvider extends WorldProviderSurface
    {
        private TestWorldProvider(BiomeProvider biomeProvider)
        {
            this.biomeProvider = biomeProvider;
        }
    }

    private static class FixedLayer extends GenLayer
    {
        private final int value;

        private FixedLayer(long seed, int value)
        {
            super(seed);
            this.value = value;
        }

        @Override
        public int[] getInts(int areaX, int areaY, int areaWidth, int areaHeight)
        {
            int[] result = new int[areaWidth * areaHeight];
            Arrays.fill(result, this.value);
            return result;
        }
    }

    private static class ReadableAnvilChunkLoader extends AnvilChunkLoader
    {
        private ReadableAnvilChunkLoader()
        {
            super(new File("."), new DataFixer(1343));
        }

        private Chunk read(World world, NBTTagCompound root)
        {
            return this.checkedReadChunkFromNBT(world, 0, 0, root);
        }
    }

    private static class TestSidedHandler implements IFMLSidedHandler
    {
        private final CompoundDataFixer dataFixer = new CompoundDataFixer(new DataFixer(1343));

        @Override
        public List<String> getAdditionalBrandingInformation()
        {
            return Collections.emptyList();
        }

        @Override
        public Side getSide()
        {
            return Side.SERVER;
        }

        @Override
        public void haltGame(String message, Throwable exception)
        {
            throw new AssertionError(message, exception);
        }

        @Override
        public void showGuiScreen(Object clientGuiElement)
        {
        }

        @Override
        public void queryUser(StartupQuery query)
        {
        }

        @Override
        public void beginServerLoading(MinecraftServer server)
        {
        }

        @Override
        public void finishServerLoading()
        {
        }

        @Override
        public File getSavesDirectory()
        {
            return new File(".");
        }

        @Override
        public MinecraftServer getServer()
        {
            return null;
        }

        @Override
        public boolean isDisplayCloseRequested()
        {
            return false;
        }

        @Override
        public boolean shouldServerShouldBeKilledQuietly()
        {
            return false;
        }

        @Override
        public void addModAsResource(ModContainer container)
        {
        }

        @Override
        public String getCurrentLanguage()
        {
            return "en_us";
        }

        @Override
        public void serverStopped()
        {
        }

        @Override
        public NetworkManager getClientToServerNetworkManager()
        {
            return null;
        }

        @Override
        public INetHandler getClientPlayHandler()
        {
            return null;
        }

        @Override
        public void fireNetRegistrationEvent(EventBus bus, NetworkManager manager, Set<String> channelSet, String channel, Side side)
        {
        }

        @Override
        public boolean shouldAllowPlayerLogins()
        {
            return true;
        }

        @Override
        public void allowLogins()
        {
        }

        @Override
        public IThreadListener getWorldThread(INetHandler net)
        {
            return null;
        }

        @Override
        public void processWindowMessages()
        {
        }

        @Override
        public String stripSpecialChars(String message)
        {
            return message;
        }

        @Override
        public void reloadRenderers()
        {
        }

        @Override
        public void fireSidedRegistryEvents()
        {
        }

        @Override
        public CompoundDataFixer getDataFixer()
        {
            return this.dataFixer;
        }

        @Override
        public boolean isDisplayVSyncForced()
        {
            return false;
        }
    }
}
