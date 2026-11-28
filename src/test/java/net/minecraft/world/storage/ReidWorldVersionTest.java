package net.minecraft.world.storage;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.fml.common.registry.ForgeTestRunner;
import net.minecraftforge.fml.common.registry.MinecraftTestHarness;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(ForgeTestRunner.class)
@ForgeTestRunner.Isolated
public class ReidWorldVersionTest
{
    private static final int REID_VERSION = Integer.MAX_VALUE / 2;

    @BeforeClass
    public static void bootstrap()
    {
        MinecraftTestHarness.bootstrap();
    }

    @Test
    public void worldVersionTagUsesReidMarker()
    {
        NBTTagCompound saved = new NBTTagCompound();
        WorldInfo.writeVersion(saved);
        NBTTagCompound version = saved.getCompoundTag("Version");

        assertEquals("1.13-JEID", version.getString("Name"));
        assertEquals(REID_VERSION, version.getInteger("Id"));
    }

    @Test
    public void reidVersionDoesNotAskToOpenWorld()
    {
        assertFalse(summaryForVersion(REID_VERSION).askToOpenWorld());
    }

    @Test
    public void otherNewerVersionsStillAskToOpenWorld()
    {
        assertTrue(summaryForVersion(1344).askToOpenWorld());
    }

    private static WorldSummary summaryForVersion(int versionId)
    {
        NBTTagCompound version = new NBTTagCompound();
        version.setString("Name", "test");
        version.setInteger("Id", versionId);
        NBTTagCompound saved = new NBTTagCompound();
        saved.setTag("Version", version);
        WorldInfo info = new WorldInfo(saved);
        return new WorldSummary(info, "test", "test", 0L, false);
    }
}
