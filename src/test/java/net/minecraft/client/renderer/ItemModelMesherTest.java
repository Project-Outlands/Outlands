package net.minecraft.client.renderer;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.client.renderer.block.model.ItemOverrideList;
import net.minecraft.client.renderer.block.model.ModelManager;
import net.minecraft.client.renderer.block.model.ModelResourceLocation;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.registry.ForgeTestRunner;
import net.minecraftforge.fml.common.registry.MinecraftTestHarness;
import net.minecraftforge.registries.ForgeRegistry;
import net.minecraftforge.registries.GameData;
import net.minecraftforge.registries.RegistryManager;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertSame;

@RunWith(ForgeTestRunner.class)
@ForgeTestRunner.Isolated
public class ItemModelMesherTest
{
    private static final int FIRST_ITEM_ID = 65536;
    private static final int SECOND_ITEM_ID = 131072;

    private static Item firstItem;
    private static Item secondItem;

    @BeforeClass
    public static void bootstrap()
    {
        MinecraftTestHarness.bootstrap();
        firstItem = new Item().setHasSubtypes(true).setRegistryName(new ResourceLocation("test", "first_model_item"));
        secondItem = new Item().setHasSubtypes(true).setRegistryName(new ResourceLocation("test", "second_model_item"));
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
    public void wideModelsAndMetadataSurviveRegistrationAndRebuild()
    {
        TestModelManager models = new TestModelManager();
        ItemModelMesher mesher = new ItemModelMesher(models);
        ModelResourceLocation firstLocation = location("first_meta_one");
        ModelResourceLocation secondLocation = location("first_meta_two");
        ModelResourceLocation collidingLocation = location("second_meta_one");
        TestModel firstModel = new TestModel();
        TestModel secondModel = new TestModel();
        TestModel collidingModel = new TestModel();
        models.models.put(firstLocation, firstModel);
        models.models.put(secondLocation, secondModel);
        models.models.put(collidingLocation, collidingModel);

        mesher.register(firstItem, 1, firstLocation);
        mesher.register(firstItem, 2, secondLocation);
        mesher.register(secondItem, 1, collidingLocation);

        assertSame(firstModel, mesher.getItemModel(new ItemStack(firstItem, 1, 1)));
        assertSame(secondModel, mesher.getItemModel(new ItemStack(firstItem, 1, 2)));
        assertSame(collidingModel, mesher.getItemModel(new ItemStack(secondItem, 1, 1)));

        TestModel rebuiltFirstModel = new TestModel();
        TestModel rebuiltSecondModel = new TestModel();
        TestModel rebuiltCollidingModel = new TestModel();
        models.models.put(firstLocation, rebuiltFirstModel);
        models.models.put(secondLocation, rebuiltSecondModel);
        models.models.put(collidingLocation, rebuiltCollidingModel);
        mesher.rebuildCache();

        assertSame(rebuiltFirstModel, mesher.getItemModel(new ItemStack(firstItem, 1, 1)));
        assertSame(rebuiltSecondModel, mesher.getItemModel(new ItemStack(firstItem, 1, 2)));
        assertSame(rebuiltCollidingModel, mesher.getItemModel(new ItemStack(secondItem, 1, 1)));
    }

    private static ModelResourceLocation location(String path)
    {
        return new ModelResourceLocation(new ResourceLocation("test", path), "inventory");
    }

    private static final class TestModelManager extends ModelManager
    {
        private final Map<ModelResourceLocation, IBakedModel> models = new HashMap<>();
        private final IBakedModel missingModel = new TestModel();

        private TestModelManager()
        {
            super(new TextureMap("test"));
        }

        @Override
        public IBakedModel getModel(ModelResourceLocation modelLocation)
        {
            return this.models.getOrDefault(modelLocation, this.missingModel);
        }

        @Override
        public IBakedModel getMissingModel()
        {
            return this.missingModel;
        }
    }

    private static final class TestModel implements IBakedModel
    {
        @Override
        public List<BakedQuad> getQuads(IBlockState state, EnumFacing side, long rand)
        {
            return Collections.emptyList();
        }

        @Override
        public boolean isAmbientOcclusion()
        {
            return false;
        }

        @Override
        public boolean isGui3d()
        {
            return false;
        }

        @Override
        public boolean isBuiltInRenderer()
        {
            return false;
        }

        @Override
        public TextureAtlasSprite getParticleTexture()
        {
            return null;
        }

        @Override
        public ItemOverrideList getOverrides()
        {
            return ItemOverrideList.NONE;
        }
    }
}
