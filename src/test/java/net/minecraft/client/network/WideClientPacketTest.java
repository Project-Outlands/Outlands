package net.minecraft.client.network;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.PropertyMap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.main.GameConfiguration;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.entity.item.EntityFallingBlock;
import net.minecraft.network.EnumPacketDirection;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.server.SPacketEntityEffect;
import net.minecraft.network.play.server.SPacketJoinGame;
import net.minecraft.network.play.server.SPacketRemoveEntityEffect;
import net.minecraft.network.play.server.SPacketSpawnObject;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import net.minecraft.stats.RecipeBook;
import net.minecraft.stats.StatisticsManager;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.Session;
import net.minecraft.world.EnumDifficulty;
import net.minecraft.world.GameType;
import net.minecraft.world.WorldType;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.fml.common.network.handshake.NetworkDispatcher;
import net.minecraftforge.fml.common.registry.ForgeTestRunner;
import net.minecraftforge.fml.common.registry.MinecraftTestHarness;
import net.minecraftforge.registries.GameData;
import net.minecraftforge.registries.WideTestRegistrations;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.net.Proxy;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

@RunWith(ForgeTestRunner.class)
@ForgeTestRunner.Isolated
public class WideClientPacketTest
{
    private static final int PLAYER_ID = 100;
    private static Potion widePotion;
    private static Block wideBlock;
    private static TestMinecraft client;

    @BeforeClass
    public static void bootstrap()
    {
        MinecraftTestHarness.bootstrap();
        DimensionManager.init();
        widePotion = new TestPotion(new ResourceLocation("test", "wide_client_potion"));
        wideBlock = new TestBlock(new ResourceLocation("test", "wide_falling_block"));
        WideTestRegistrations.registerPotion(300, widePotion);
        WideTestRegistrations.registerBlock(5000, wideBlock);
        GameData.freezeData();

        client = new TestMinecraft();
        client.gameSettings = new GameSettings(client, new File("build/test-wide-client"));
        client.connect();
    }

    @AfterClass
    public static void shutdown()
    {
        if (client != null)
        {
            client.network.close();
        }
    }

    @Test
    public void entityEffectHandlerUsesWidePotionId()
    {
        SPacketEntityEffect packet = new SPacketEntityEffect(PLAYER_ID, new PotionEffect(widePotion, 1234, 3, true, false));
        client.connection.handleEntityEffect(packet);

        PotionEffect effect = client.player.getActivePotionEffect(widePotion);
        assertNotNull(effect);
        assertEquals(1234, effect.getDuration());
        assertEquals(3, effect.getAmplifier());
        assertTrue(effect.getIsAmbient());
        assertFalse(effect.doesShowParticles());
    }

    @Test
    public void removeEntityEffectHandlerUsesWidePotionId() throws Exception
    {
        client.player.addPotionEffect(new PotionEffect(widePotion, 1234));
        SPacketRemoveEntityEffect original = new SPacketRemoveEntityEffect(PLAYER_ID, widePotion);
        PacketBuffer buffer = new PacketBuffer(Unpooled.buffer());
        try
        {
            original.writePacketData(buffer);
            SPacketRemoveEntityEffect decoded = new SPacketRemoveEntityEffect();
            decoded.readPacketData(buffer);
            client.connection.handleRemoveEntityEffect(decoded);

            assertNull(client.player.getActivePotionEffect(widePotion));
        }
        finally
        {
            buffer.release();
        }
    }

    @Test
    public void fallingBlockHandlerUsesWideStateId()
    {
        IBlockState state = wideBlock.getDefaultState();
        EntityFallingBlock source = new EntityFallingBlock(client.world, 1.0, 64.0, 1.0, state);
        source.setEntityId(1000);
        SPacketSpawnObject packet = new SPacketSpawnObject(source, 70, Block.getStateId(state));

        client.connection.handleSpawnObject(packet);

        EntityFallingBlock spawned = (EntityFallingBlock) client.world.getEntityByID(1000);
        assertNotNull(spawned);
        assertSame(state, spawned.getBlock());
        assertEquals(0, packet.getData());
    }

    private static GameConfiguration createConfiguration()
    {
        File gameDir = new File("build/test-wide-client");
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
            this.connection.handleJoinGame(new SPacketJoinGame(
                    PLAYER_ID, GameType.SURVIVAL, false, 0, EnumDifficulty.PEACEFUL, 20, WorldType.DEFAULT, false));
        }

        @Override
        public void loadWorld(WorldClient worldClientIn)
        {
            this.world = worldClientIn;
            this.player = new EntityPlayerSP(this, worldClientIn, this.connection, new StatisticsManager(), new RecipeBook());
        }

        @Override
        public void displayGuiScreen(GuiScreen guiScreenIn)
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
        public void sendPacket(Packet<?> packetIn)
        {
        }

        private void close()
        {
            this.channel.finishAndReleaseAll();
        }
    }

    private static class TestPotion extends Potion
    {
        private TestPotion(ResourceLocation name)
        {
            super(false, 0);
            setRegistryName(name);
        }
    }

    private static class TestBlock extends Block
    {
        private TestBlock(ResourceLocation name)
        {
            super(Material.ROCK);
            setRegistryName(name);
        }
    }
}
