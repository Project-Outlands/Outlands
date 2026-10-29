package net.minecraft.client.renderer;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.PropertyMap;
import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.block.Block;
import net.minecraft.block.SoundType;
import net.minecraft.block.material.Material;
import net.minecraft.block.properties.PropertyInteger;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.main.GameConfiguration;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.particle.ParticleManager;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.entity.Entity;
import net.minecraft.init.SoundEvents;
import net.minecraft.network.EnumPacketDirection;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.Session;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvent;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.EnumDifficulty;
import net.minecraft.world.GameType;
import net.minecraft.world.World;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.fml.common.network.handshake.NetworkDispatcher;
import net.minecraftforge.fml.common.registry.ForgeTestRunner;
import net.minecraftforge.fml.common.registry.MinecraftTestHarness;
import net.minecraftforge.registries.GameData;
import net.minecraftforge.registries.WideTestRegistrations;

import top.outlands.foundation.boot.MainThreadExecutor;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.DisplayMode;

import javax.annotation.Nullable;
import java.io.File;
import java.net.Proxy;
import java.util.UUID;

import static org.junit.Assert.assertSame;

@RunWith(ForgeTestRunner.class)
@ForgeTestRunner.Isolated
public class WideBlockEventTest
{
    private static final PropertyInteger TEST_STATE = PropertyInteger.create("test_state", 0, 15);
    private static final BlockPos EVENT_POS = new BlockPos(1, 64, 1);
    private static MetadataBlock legacyBlock;
    private static MetadataBlock wideBlock;
    private static TestMinecraft client;
    private static boolean ownsDisplay;

    @BeforeClass
    public static void bootstrap()
    {
        MinecraftTestHarness.bootstrap();
        DimensionManager.init();
        legacyBlock = new MetadataBlock(new ResourceLocation("test", "legacy_block_event"));
        wideBlock = new MetadataBlock(new ResourceLocation("test", "wide_block_event"));
        WideTestRegistrations.registerBlock(300, legacyBlock);
        WideTestRegistrations.registerBlock(5000, wideBlock);
        GameData.freezeData();

        MainThreadExecutor.init();
        if (!Display.isCreated())
        {
            ownsDisplay = true;
            Display.setDisplayMode(new DisplayMode(1, 1));
            Display.create();
        }
        client = new TestMinecraft();
        client.gameSettings = new GameSettings(client, new File("build/test-wide-block-event"));
        client.renderGlobal = new TestRenderGlobal(client);
        client.effectRenderer = new RecordingParticleManager(client.getTextureManager());
        client.connect();
    }

    @AfterClass
    public static void shutdown()
    {
        try
        {
            if (client != null)
            {
                client.close();
            }
        }
        finally
        {
            if (ownsDisplay)
            {
                Display.destroy();
            }
        }
    }

    @Test
    public void vanillaBlockEventUsesSameStateForSoundAndParticles()
    {
        assertBlockBreakEvent(legacyBlock, 7);
    }

    @Test
    public void wideBlockEventUsesSameStateForSoundAndParticles()
    {
        assertBlockBreakEvent(wideBlock, 15);
    }

    private static void assertBlockBreakEvent(MetadataBlock block, int metadata)
    {
        IBlockState state = block.getStateFromMeta(metadata);
        block.soundState = null;
        client.effectRenderer().destroyState = null;
        client.recordingWorld().breakSound = null;

        client.renderGlobal.playEvent(null, 2001, EVENT_POS, Block.getStateId(state));

        assertSame(state, block.soundState);
        assertSame(block.soundState, client.effectRenderer().destroyState);
        assertSame(SoundEvents.BLOCK_STONE_BREAK, client.recordingWorld().breakSound);
    }

    private static GameConfiguration createConfiguration()
    {
        File gameDir = new File("build/test-wide-block-event");
        Session session = new Session("TestPlayer", UUID.randomUUID().toString(), "test", "mojang");
        GameConfiguration.UserInformation user = new GameConfiguration.UserInformation(
                session, new PropertyMap(), new PropertyMap(), Proxy.NO_PROXY);
        GameConfiguration.DisplayInformation display = new GameConfiguration.DisplayInformation(1, 1, false, false);
        GameConfiguration.FolderInformation folders = new GameConfiguration.FolderInformation(
                gameDir, new File(gameDir, "resourcepacks"), new File(gameDir, "assets"), null);
        GameConfiguration.GameInformation game = new GameConfiguration.GameInformation(false, "test", "test");
        GameConfiguration.ServerInformation server = new GameConfiguration.ServerInformation(null, 0);
        return new GameConfiguration(user, display, folders, game, server);
    }

    private static class TestMinecraft extends Minecraft
    {
        private final TestNetworkManager network = new TestNetworkManager();
        private final TextureManager textureManager = new TestTextureManager();
        private RenderManager renderManager;
        private final TextureMap textureMap = new TextureMap("textures/blocks");
        private NetHandlerPlayClient connection;

        private TestMinecraft()
        {
            super(createConfiguration());
        }

        private void connect()
        {
            NetworkDispatcher.allocAndSet(this.network);
            GameProfile profile = new GameProfile(UUID.randomUUID(), "TestPlayer");
            this.connection = new NetHandlerPlayClient(this, null, this.network, profile);
            this.world = new RecordingWorldClient(this.connection, this);
            this.renderGlobal.setWorldAndLoadRenderers(this.world);
        }

        private RecordingParticleManager effectRenderer()
        {
            return (RecordingParticleManager) this.effectRenderer;
        }

        private RecordingWorldClient recordingWorld()
        {
            return (RecordingWorldClient) this.world;
        }

        private void close()
        {
            try
            {
                if (this.renderGlobal != null)
                {
                    this.renderGlobal.setWorldAndLoadRenderers(null);
                }
            }
            finally
            {
                this.network.close();
            }
        }

        @Override
        public TextureManager getTextureManager()
        {
            return this.textureManager;
        }

        @Override
        public RenderManager getRenderManager()
        {
            if (this.renderManager == null)
            {
                this.renderManager = new RenderManager(this.textureManager, null);
            }
            return this.renderManager;
        }

        @Override
        public TextureMap getTextureMapBlocks()
        {
            return this.textureMap;
        }
    }

    private static class TestRenderGlobal extends RenderGlobal
    {
        private TestRenderGlobal(Minecraft client)
        {
            super(client);
        }

        @Override
        public void loadRenderers()
        {
        }
    }

    private static class RecordingWorldClient extends WorldClient
    {
        private SoundEvent breakSound;

        private RecordingWorldClient(NetHandlerPlayClient connection, Minecraft client)
        {
            super(connection, new WorldSettings(0L, GameType.SURVIVAL, false, false, WorldType.DEFAULT),
                    0, EnumDifficulty.PEACEFUL, client.profiler);
        }

        @Override
        public void playSound(BlockPos pos, SoundEvent sound, SoundCategory category, float volume, float pitch,
                boolean distanceDelay)
        {
            this.breakSound = sound;
        }
    }

    private static class RecordingParticleManager extends ParticleManager
    {
        private IBlockState destroyState;

        private RecordingParticleManager(TextureManager textureManager)
        {
            super(null, textureManager);
        }

        @Override
        public void addBlockDestroyEffects(BlockPos pos, IBlockState state)
        {
            this.destroyState = state;
        }
    }

    private static class TestTextureManager extends TextureManager
    {
        private TestTextureManager()
        {
            super(null);
        }

        @Override
        public void bindTexture(ResourceLocation resource)
        {
        }
    }

    private static class TestNetworkManager extends NetworkManager
    {
        private final EmbeddedChannel channel = new EmbeddedChannel();

        private TestNetworkManager()
        {
            super(EnumPacketDirection.CLIENTBOUND);
        }

        @Override
        public Channel channel()
        {
            return this.channel;
        }

        @Override
        public void sendPacket(Packet<?> packet)
        {
        }

        private void close()
        {
            this.channel.finishAndReleaseAll();
        }
    }

    private static class MetadataBlock extends Block
    {
        private IBlockState soundState;

        private MetadataBlock(ResourceLocation name)
        {
            super(Material.ROCK);
            setRegistryName(name);
        }

        @Override
        protected BlockStateContainer createBlockState()
        {
            return new BlockStateContainer(this, TEST_STATE);
        }

        @Override
        public int getMetaFromState(IBlockState state)
        {
            return state.getValue(TEST_STATE);
        }

        @Override
        public IBlockState getStateFromMeta(int meta)
        {
            return this.getDefaultState().withProperty(TEST_STATE, meta);
        }

        @Override
        public SoundType getSoundType(IBlockState state, World world, BlockPos pos, @Nullable Entity entity)
        {
            this.soundState = state;
            return SoundType.STONE;
        }
    }
}
