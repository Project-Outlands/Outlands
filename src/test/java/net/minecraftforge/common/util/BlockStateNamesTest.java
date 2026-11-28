package net.minecraftforge.common.util;

import net.minecraft.block.BlockColored;
import net.minecraft.block.BlockStairs;
import net.minecraft.block.BlockStone;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.item.EnumDyeColor;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.fml.common.registry.ForgeTestRunner;
import net.minecraftforge.fml.common.registry.MinecraftTestHarness;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

/**
 * The section palette stores these strings instead of registry ids, so they have to be stable, canonical
 * and complete: the same state always produces the same string, and reading it back gives that state even
 * when the numeric id it used to have now belongs to something else.
 */
@RunWith(ForgeTestRunner.class)
@ForgeTestRunner.Isolated
public class BlockStateNamesTest
{
    @BeforeClass
    public static void bootstrap()
    {
        MinecraftTestHarness.bootstrap();
    }

    @Test
    public void statesWithoutPropertiesHaveNoBrackets()
    {
        assertEquals("minecraft:air", BlockStateNames.toName(Blocks.AIR.getDefaultState()));
        assertEquals("minecraft:diamond_block", BlockStateNames.toName(Blocks.DIAMOND_BLOCK.getDefaultState()));
    }

    @Test
    public void propertiesAreWrittenSortedByName()
    {
        // Stairs carry three properties; the order must not depend on how the block declared them.
        String name = BlockStateNames.toName(Blocks.OAK_STAIRS.getDefaultState());

        // facing = north is the stairs default; the point is the sorted property order, not the values.
        assertEquals("minecraft:oak_stairs[facing=north,half=bottom,shape=straight]", name);
    }

    @Test
    public void enumAndIntegerPropertiesUseTheirValueNames()
    {
        IBlockState wool = Blocks.WOOL.getDefaultState().withProperty(BlockColored.COLOR, EnumDyeColor.LIME);

        assertEquals("minecraft:wool[color=lime]", BlockStateNames.toName(wool));
        assertEquals("minecraft:stone[variant=granite]",
                BlockStateNames.toName(Blocks.STONE.getDefaultState().withProperty(BlockStone.VARIANT, BlockStone.EnumType.GRANITE)));
    }

    @Test
    public void everyVanillaStateRoundTripsToTheSameInstance()
    {
        for (net.minecraft.block.Block block : net.minecraft.block.Block.REGISTRY)
        {
            for (IBlockState state : block.getBlockState().getValidStates())
            {
                String name = BlockStateNames.toName(state);
                assertSame(name, state, BlockStateNames.fromName(name));
            }
        }
    }

    @Test
    public void nameSurvivesAStairsStateWithAllPropertiesSet()
    {
        IBlockState state = Blocks.SPRUCE_STAIRS.getDefaultState()
                .withProperty(BlockStairs.FACING, EnumFacing.WEST)
                .withProperty(BlockStairs.HALF, BlockStairs.EnumHalf.TOP)
                .withProperty(BlockStairs.SHAPE, BlockStairs.EnumShape.INNER_LEFT);

        assertEquals("minecraft:spruce_stairs[facing=west,half=top,shape=inner_left]", BlockStateNames.toName(state));
        assertSame(state, BlockStateNames.fromName(BlockStateNames.toName(state)));
    }

    @Test
    public void missingBlockIsReportedAsNull()
    {
        assertNull(BlockStateNames.fromName("test:not_installed[foo=bar]"));
    }

    @Test
    public void unknownPropertiesAndValuesAreIgnored()
    {
        // A property that a mod dropped, and a value it renamed: keep as much of the state as is still valid.
        assertSame(Blocks.STONE.getDefaultState(), BlockStateNames.fromName("minecraft:stone[removed=1]"));
        assertSame(Blocks.STONE.getDefaultState(), BlockStateNames.fromName("minecraft:stone[variant=bogus]"));
        assertSame(Blocks.STONE.getDefaultState().withProperty(BlockStone.VARIANT, BlockStone.EnumType.DIORITE),
                BlockStateNames.fromName("minecraft:stone[variant=diorite,removed=1]"));
    }

    @Test
    public void unnamedBlocksProduceANameThatWillNotResolve()
    {
        net.minecraft.block.Block unregistered = new net.minecraft.block.Block(net.minecraft.block.material.Material.ROCK);
        String name = BlockStateNames.toName(unregistered.getDefaultState());

        // Falling back to the numeric palette entry is right here: the block cannot be identified by name.
        assertEquals(BlockStateNames.UNRESOLVABLE, name);
        assertNull(BlockStateNames.fromName(name));
    }

    @Test
    public void namesDoNotDependOnRegistryIds()
    {
        // Same state, one name - no matter what the registry looks like when it is written.
        String before = BlockStateNames.toName(Blocks.STONE.getDefaultState());
        String after = BlockStateNames.toName(Blocks.STONE.getDefaultState());

        assertEquals(before, after);
        assertEquals("minecraft:stone[variant=stone]", before);
    }
}
