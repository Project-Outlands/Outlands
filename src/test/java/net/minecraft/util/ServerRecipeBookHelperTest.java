package net.minecraft.util;

import com.mojang.authlib.GameProfile;
import java.io.File;
import java.net.Proxy;
import java.util.Map;
import java.util.UUID;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Items;
import net.minecraft.inventory.ContainerPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.item.crafting.Ingredient;
import net.minecraft.item.crafting.ShapelessRecipes;
import net.minecraft.network.EnumPacketDirection;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.profiler.Profiler;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.PlayerInteractionManager;
import net.minecraft.server.management.PlayerList;
import net.minecraft.world.EnumDifficulty;
import net.minecraft.world.GameType;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraft.world.chunk.storage.AnvilSaveHandler;
import net.minecraft.world.storage.WorldInfo;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.fml.common.registry.ForgeTestRunner;
import net.minecraftforge.fml.common.registry.MinecraftTestHarness;
import net.minecraftforge.registries.ForgeRegistry;
import net.minecraftforge.registries.GameData;
import net.minecraftforge.registries.RegistryManager;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

@RunWith(ForgeTestRunner.class)
@ForgeTestRunner.Isolated
public class ServerRecipeBookHelperTest
{
    private static final int WIDE_ITEM_ID = 65792;

    private static Item wideItem;
    private static ShapelessRecipes recipe;
    private static TestServer server;
    private static WorldServer world;
    private static EntityPlayerMP player;

    @BeforeClass
    public static void bootstrap()
    {
        MinecraftTestHarness.bootstrap();
        DimensionManager.init();
        wideItem = new Item().setRegistryName(new ResourceLocation("test", "wide_server_recipe_item"));
        ForgeRegistry<Item> items = RegistryManager.ACTIVE.getRegistry(GameData.ITEMS);
        items.register(wideItem);
        recipe = recipe(wideItem);
        ForgeRegistry<IRecipe> recipes = RegistryManager.ACTIVE.getRegistry(GameData.RECIPES);
        recipes.register(recipe);
        Map<ResourceLocation, ForgeRegistry.Snapshot> snapshots = RegistryManager.ACTIVE.takeSnapshot(false);
        snapshots.get(GameData.ITEMS).ids.put(wideItem.getRegistryName(), WIDE_ITEM_ID);
        GameData.injectSnapshot(snapshots, false, true);
        GameData.freezeData();

        server = new TestServer();
        server.setPlayerList(new TestPlayerList(server));
        WorldSettings settings = new WorldSettings(0, GameType.ADVENTURE, false, false, WorldType.DEFAULT);
        WorldInfo info = new WorldInfo(settings, "ServerRecipeBookHelperTest");
        File gameDirectory = new File("build/test-server-recipe");
        world = new WorldServer(
                server,
                new AnvilSaveHandler(gameDirectory, "world", false, null),
                info,
                0,
                new Profiler());
        world.init();
        server.worlds = new WorldServer[] {world};
    }

    @Before
    public void createPlayer()
    {
        player = new EntityPlayerMP(
                server,
                world,
                new GameProfile(UUID.randomUUID(), "RecipeTestPlayer"),
                new PlayerInteractionManager(world));
        new NetHandlerPlayServer(server, new TestNetworkManager(), player);
        player.getRecipeBook().unlock(recipe);
    }

    @Test
    public void highIdIngredientIsCollectedAndPlaced()
    {
        player.inventory.setInventorySlotContents(0, new ItemStack(wideItem));

        new ServerRecipeBookHelper().func_194327_a(player, recipe, false);

        ContainerPlayer container = (ContainerPlayer)player.openContainer;
        ItemStack placed = container.craftMatrix.getStackInSlot(0);
        assertSame(wideItem, placed.getItem());
        assertEquals(1, placed.getCount());
        assertTrue(player.inventory.getStackInSlot(0).isEmpty());
    }

    @Test
    public void highIdIngredientDoesNotAcceptLegacyCollision()
    {
        player.inventory.setInventorySlotContents(0, new ItemStack(Items.IRON_SHOVEL));

        new ServerRecipeBookHelper().func_194327_a(player, recipe, false);

        ContainerPlayer container = (ContainerPlayer)player.openContainer;
        assertTrue(container.craftMatrix.isEmpty());
        assertSame(Items.IRON_SHOVEL, player.inventory.getStackInSlot(0).getItem());
        assertEquals(1, player.inventory.getStackInSlot(0).getCount());
    }

    private static ShapelessRecipes recipe(Item required)
    {
        NonNullList<Ingredient> ingredients = NonNullList.create();
        ingredients.add(Ingredient.fromStacks(new ItemStack(required)));
        ShapelessRecipes result = new ShapelessRecipes("", new ItemStack(required), ingredients);
        result.setRegistryName(new ResourceLocation("test", "wide_server_recipe"));
        return result;
    }

    private static class TestServer extends MinecraftServer
    {
        private TestServer()
        {
            super(new File("build/test-server-recipe"), Proxy.NO_PROXY, null, null, null, null, null);
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
            return GameType.ADVENTURE;
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

    private static class TestPlayerList extends PlayerList
    {
        private TestPlayerList(MinecraftServer server)
        {
            super(server);
        }
    }

    private static class TestNetworkManager extends NetworkManager
    {
        private TestNetworkManager()
        {
            super(EnumPacketDirection.SERVERBOUND);
        }

        @Override
        public void sendPacket(Packet<?> packetIn)
        {
        }
    }
}
