package net.minecraftforge.registries;

import java.io.File;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.profiler.Profiler;
import net.minecraft.util.datafix.DataFixer;
import net.minecraft.world.World;
import net.minecraft.world.WorldProviderSurface;
import net.minecraft.world.chunk.BlockStateContainer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.chunk.NibbleArray;
import net.minecraft.world.chunk.storage.AnvilChunkLoader;
import net.minecraft.world.storage.WorldInfo;
import net.minecraftforge.fml.common.registry.ForgeTestRunner;
import net.minecraftforge.fml.common.registry.MinecraftTestHarness;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

@RunWith(ForgeTestRunner.class)
@ForgeTestRunner.Isolated
public class SectionPaletteValidationTest
{
    @BeforeClass
    public static void bootstrap()
    {
        MinecraftTestHarness.bootstrap();
    }

    @Test
    public void rejectsEmptyPalette()
    {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> decode(new int[0], new byte[4096]));

        assertTrue(exception.getMessage().contains("Palette"));
        assertTrue(exception.getMessage().contains("0"));
    }

    @Test
    public void rejectsOversizedPalette()
    {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> decode(new int[4097], new byte[4096]));

        assertTrue(exception.getMessage().contains("Palette"));
        assertTrue(exception.getMessage().contains("4097"));
    }

    @Test
    public void rejectsInvalidBlocksLength()
    {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> decode(new int[] {0}, new byte[4095]));

        assertTrue(exception.getMessage().contains("Blocks"));
        assertTrue(exception.getMessage().contains("4095"));
    }

    @Test
    public void rejectsPaletteIndexOutsidePalette()
    {
        byte[] blocks = new byte[4096];
        blocks[0] = 1;
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> decode(new int[] {0}, blocks));

        assertTrue(exception.getMessage().contains("Palette"));
        assertTrue(exception.getMessage().contains("16"));
    }

    @Test
    public void anvilReportsChunkAndSectionForInvalidPalette()
    {
        NBTTagCompound root = new NBTTagCompound();
        NBTTagCompound level = new NBTTagCompound();
        root.setTag("Level", level);
        level.setInteger("xPos", 12);
        level.setInteger("zPos", -7);
        level.setIntArray("HeightMap", new int[256]);
        NBTTagCompound section = new NBTTagCompound();
        section.setByte("Y", (byte)5);
        section.setIntArray("Palette", new int[0]);
        section.setByteArray("Blocks", new byte[4096]);
        section.setByteArray("Data", new byte[2048]);
        NBTTagList sections = new NBTTagList();
        sections.appendTag(section);
        level.setTag("Sections", sections);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> new ReadableAnvilChunkLoader().read(new TestWorld(), 12, -7, root));

        assertTrue(exception.getMessage().contains("12"));
        assertTrue(exception.getMessage().contains("-7"));
        assertTrue(exception.getMessage().contains("5"));
        assertTrue(exception.getCause() instanceof IllegalArgumentException);
        assertTrue(exception.getCause().getMessage().contains("Palette"));
    }

    @Test
    public void anvilReportsChunkAndSectionForInvalidData()
    {
        NBTTagCompound section = createSection();
        section.setByteArray("Data", new byte[2047]);

        assertAnvilContext(section);
    }

    @Test
    public void anvilReportsChunkAndSectionForInvalidAdd2()
    {
        NBTTagCompound section = createSection();
        section.setByteArray("Data", new byte[2048]);
        section.setByteArray("Add2", new byte[2047]);

        assertAnvilContext(section);
    }

    private static NBTTagCompound createSection()
    {
        NBTTagCompound section = new NBTTagCompound();
        section.setByte("Y", (byte)5);
        section.setByteArray("Blocks", new byte[4096]);
        return section;
    }

    private static void assertAnvilContext(NBTTagCompound section)
    {
        NBTTagCompound root = new NBTTagCompound();
        NBTTagCompound level = new NBTTagCompound();
        root.setTag("Level", level);
        level.setInteger("xPos", 12);
        level.setInteger("zPos", -7);
        level.setIntArray("HeightMap", new int[256]);
        NBTTagList sections = new NBTTagList();
        sections.appendTag(section);
        level.setTag("Sections", sections);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> new ReadableAnvilChunkLoader().read(new TestWorld(), 12, -7, root));

        assertTrue(exception.getMessage().contains("12"));
        assertTrue(exception.getMessage().contains("-7"));
        assertTrue(exception.getMessage().contains("5"));
        assertTrue(exception.getCause() instanceof IllegalArgumentException);
        assertTrue(exception.getCause().getMessage().contains("2047"));
    }

    private static void decode(int[] palette, byte[] blocks)
    {
        BlockStateContainer container = new BlockStateContainer();
        container.setTemporaryPalette(palette);
        container.setDataFromNBT(blocks, new NibbleArray(), null);
    }

    private static class TestWorld extends World
    {
        private TestWorld()
        {
            super(null, new WorldInfo(new NBTTagCompound()), new WorldProviderSurface(), new Profiler(), false);
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

    private static class ReadableAnvilChunkLoader extends AnvilChunkLoader
    {
        private ReadableAnvilChunkLoader()
        {
            super(new File("."), new DataFixer(1343));
        }

        private Chunk read(World world, int x, int z, NBTTagCompound root)
        {
            return this.checkedReadChunkFromNBT(world, x, z, root);
        }
    }
}
