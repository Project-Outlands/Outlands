package net.minecraftforge.common.property;

import java.util.List;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.properties.PropertyBool;
import net.minecraft.block.properties.PropertyEnum;
import net.minecraft.block.properties.PropertyInteger;
import net.minecraft.block.state.BlockStateBase;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.IStringSerializable;
import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code StateImplementation.withProperty} used to read a per state {@code ImmutableTable}; it now computes the
 * neighbour index from the container's property grid. These tests pin the observable behaviour: every property
 * change lands on the state the old table would have returned, the error paths still throw the same way, and
 * Forge's extended states (which wrap a clean state) keep their position.
 */
class BlockStateNeighbourhoodTest
{
    enum Shape implements IStringSerializable
    {
        STRAIGHT,
        CORNER;

        @Override
        public String getName()
        {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    private static final PropertyBool POWERED = PropertyBool.create("powered");
    private static final PropertyInteger AGE = PropertyInteger.create("age", 0, 2);
    private static final PropertyEnum<Shape> SHAPE = PropertyEnum.create("shape", Shape.class);

    /**
     * No real {@link Block}: constructing one reads {@code SoundType.STONE}, which pulls in SoundEvents and the sound
     * registry, and other tests in this project legitimately rearrange the registries. A container only needs a block
     * for its error messages, and {@code createState} accepts null, so the grid can be tested on its own.
     */
    private static BlockStateContainer container()
    {
        return new BlockStateContainer(null, POWERED, AGE, SHAPE);
    }

    @Test
    void everyPropertyChangeFindsTheStateTheTableWouldHaveFound()
    {
        BlockStateContainer container = container();
        assertEquals(2 * 3 * 2, container.getValidStates().size());

        for (IBlockState state : container.getValidStates())
        {
            for (IProperty<?> property : state.getPropertyKeys())
            {
                for (Object value : property.getAllowedValues())
                {
                    IBlockState expected = stateWith(container, state, property, value);
                    IBlockState actual = withProperty(state, property, value);
                    assertSame(expected, actual, property.getName() + "=" + value + " from " + state);
                }
            }
        }
    }

    @Test
    void settingTheValueItAlreadyHasReturnsTheSameInstance()
    {
        BlockStateContainer container = container();

        for (IBlockState state : container.getValidStates())
        {
            for (IProperty<?> property : state.getPropertyKeys())
            {
                assertSame(state, withProperty(state, property, state.getValue(property)));
            }
        }
    }

    @Test
    void cyclingReachesEveryValueAndComesBack()
    {
        BlockStateContainer container = container();
        IBlockState state = container.getBaseState();
        Shape shape = state.getValue(SHAPE);
        boolean powered = state.getValue(POWERED);
        int seen = 0;

        for (int i = 0; i < 3; ++i)
        {
            assertEquals(i, state.getValue(AGE));
            assertSame(shape, state.getValue(SHAPE));
            assertEquals(powered, state.getValue(POWERED));
            state = state.cycleProperty(AGE);
            ++seen;
        }

        assertEquals(3, seen);
        assertEquals(0, state.getValue(AGE));
    }

    @Test
    void disallowedValueThrows()
    {
        IBlockState state = container().getBaseState();

        assertThrows(IllegalArgumentException.class, () -> state.withProperty(AGE, 7));
        assertThrows(IllegalArgumentException.class, () -> state.withProperty(AGE, -1));
    }

    @Test
    void aPropertyInstanceWithTheSameNameIsNotForeign()
    {
        // PropertyBool/PropertyInteger/PropertyEnum compare by name (and value class), so a second instance of the
        // same property is the same key. Vanilla's map lookup found it too, and withProperty must keep doing that.
        BlockStateContainer container = container();
        IBlockState state = container.getBaseState();
        IProperty<Boolean> sameName = PropertyBool.create("powered");

        assertSame(state.withProperty(POWERED, true), withProperty(state, sameName, true));
    }

    @Test
    void twoContainersDoNotShareTheirGrids()
    {
        BlockStateContainer first = container();
        BlockStateContainer second = container();
        IBlockState fromFirst = first.getValidStates().get(1);
        IBlockState fromSecond = second.getValidStates().get(1);

        // Same shape, different instances: the grid of one must never answer for the other.
        assertNotSame(fromFirst, fromSecond);
        for (IBlockState state : second.getValidStates())
        {
            assertTrue(second.getValidStates().contains(state.withProperty(AGE, 2)));
        }
        assertSame(first.getValidStates().get(1), fromFirst);
    }

    @Test
    void equalPropertyEnumsHashEqually()
    {
        // PropertyEnum caches its hash; two separate instances with the same name, class and values must still
        // agree, or a state's property map would stop resolving a property built elsewhere.
        PropertyEnum<Shape> first = PropertyEnum.create("shape", Shape.class);
        PropertyEnum<Shape> second = PropertyEnum.create("shape", Shape.class);

        assertNotSame(first, second);
        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertEquals(first.hashCode(), first.hashCode());
        assertNotEquals(first.hashCode(), PropertyEnum.create("kind", Shape.class).hashCode());
    }

    @Test
    void hashCodeIsTheIdentityHash()
    {
        for (IBlockState state : container().getValidStates())
        {
            assertEquals(System.identityHashCode(state), state.hashCode());
        }
    }

    @Test
    void thePerStateTableIsNoLongerBuilt()
    {
        for (IBlockState state : container().getValidStates())
        {
            assertEquals(null, ((BlockStateBase) state).getPropertyValueTable());
        }
    }

    @Test
    void extendedStatesKeepTheirPosition()
    {
        ExtendedBlockState container = new ExtendedBlockState(null,
                new IProperty<?>[] { POWERED, AGE, SHAPE },
                new IUnlistedProperty<?>[] { TestProperty.INSTANCE });

        // Start from a clean state that carries an unlisted value, then change a listed property: the result must
        // be the same state the plain table lookups would have produced, still carrying the unlisted value.
        IExtendedBlockState extension = ((IExtendedBlockState) container.getBaseState()).withProperty(TestProperty.INSTANCE, Boolean.TRUE);
        assertTrue(extension.getUnlistedProperties().get(TestProperty.INSTANCE).orElse(null) != null);

        IBlockState aged = extension.withProperty(AGE, 2);
        assertEquals(2, aged.getValue(AGE));
        assertEquals(Boolean.TRUE, ((IExtendedBlockState) aged).getUnlistedProperties().get(TestProperty.INSTANCE).orElse(null));
        assertNotNull(aged);
    }

    /**
     * The shape Forge's extended states and IC2Classic's data carrying states use: wrap another state and forward
     * the (now always null) table. Such a state has no grid position, so withProperty has to find its container.
     */
    static final class WrappingState extends BlockStateContainer.StateImplementation
    {
        WrappingState(IBlockState source)
        {
            super(source.getBlock(), source.getProperties(), ((BlockStateBase) source).getPropertyValueTable());
        }
    }

    /**
     * IC2Classic's BlockStateContainerIC2 does not use the container's own state list: it calls the super
     * constructor with no properties, builds its own states, and calls buildPropertyValueTable() for each of them
     * while the block is still under construction (so {@code block.getBlockState()} is still null). Its
     * data-carrying wrapper states then forward that table. Everything here happens with no block at all.
     */
    @Test
    void statesThatBuiltTheirOwnTableStillAnswerFromIt()
    {
        BlockStateContainer container = container();
        IBlockState base = container.getBaseState();
        Map<Map<IProperty<?>, Comparable<?>>, BlockStateContainer.StateImplementation> states = Maps.newHashMap();

        for (IBlockState state : container.getValidStates())
        {
            states.put(state.getProperties(), (BlockStateContainer.StateImplementation) state);
        }

        WrappingState own = new WrappingState(base);
        own.buildPropertyValueTable(states);

        assertSame(stateWith(container, base, AGE, 2), withProperty(own, AGE, 2));
        assertSame(stateWith(container, base, SHAPE, Shape.CORNER), withProperty(own, SHAPE, Shape.CORNER));
        assertThrows(IllegalArgumentException.class, () -> own.withProperty(AGE, 9));
    }

    @Test
    void wrappingStatesResolveTheirGridThroughTheBlock() throws Exception
    {
        Block block = bareBlock();
        BlockStateContainer container = new BlockStateContainer(block, POWERED, AGE, SHAPE);
        setBlockState(block, container);

        IBlockState source = container.getValidStates().get(0);
        IBlockState wrapper = new WrappingState(source);

        // Setting the value it already has short circuits before any lookup, wrapper or not.
        assertSame(wrapper, withProperty(wrapper, AGE, source.getValue(AGE)));
        assertSame(stateWith(container, source, AGE, 2), withProperty(wrapper, AGE, 2));
        assertSame(stateWith(container, source, SHAPE, Shape.CORNER), withProperty(wrapper, SHAPE, Shape.CORNER));
        assertThrows(IllegalArgumentException.class, () -> wrapper.withProperty(AGE, 9));
    }

    /**
     * A Block whose constructor never ran: the real one reads {@code SoundType.STONE}, which initialises SoundEvents
     * and requires the sound registry, and other tests in this project rearrange the registries. Only the container
     * lookup (which needs {@code block.blockState}) matters here, and that field is written below.
     */
    private static Block bareBlock() throws Exception
    {
        return (Block) unsafe().allocateInstance(Block.class);
    }

    private static void setBlockState(Block block, BlockStateContainer container) throws Exception
    {
        sun.misc.Unsafe unsafe = unsafe();
        unsafe.putObject(block, unsafe.objectFieldOffset(Block.class.getDeclaredField("blockState")), container);
    }

    private static sun.misc.Unsafe unsafe() throws Exception
    {
        java.lang.reflect.Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (sun.misc.Unsafe) field.get(null);
    }

    private static IBlockState stateWith(BlockStateContainer container, IBlockState state, IProperty<?> property, Object value)
    {
        ImmutableMap.Builder<IProperty<?>, Comparable<?>> builder = ImmutableMap.builder();

        for (Map.Entry<IProperty<?>, Comparable<?>> entry : state.getProperties().entrySet())
        {
            builder.put(entry.getKey(), entry.getKey() == property ? (Comparable<?>) value : entry.getValue());
        }

        ImmutableMap<IProperty<?>, Comparable<?>> wanted = builder.build();

        for (IBlockState candidate : container.getValidStates())
        {
            if (candidate.getProperties().equals(wanted))
            {
                assertFalse(candidate == null);
                return candidate;
            }
        }

        throw new AssertionError("No state has " + wanted);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Comparable<T>> IBlockState withProperty(IBlockState state, IProperty<?> property, Object value)
    {
        return state.withProperty((IProperty<T>) property, (T) value);
    }

    /** Minimal unlisted property, the enum-free shape Forge's own tests use. */
    static final class TestProperty implements IUnlistedProperty<Boolean>
    {
        static final TestProperty INSTANCE = new TestProperty();

        @Override
        public String getName()
        {
            return "render";
        }

        @Override
        public boolean isValid(Boolean value)
        {
            return true;
        }

        @Override
        public Class<Boolean> getType()
        {
            return Boolean.class;
        }

        @Override
        public String valueToString(Boolean value)
        {
            return value.toString();
        }
    }
}
