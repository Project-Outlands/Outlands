package net.minecraftforge.common.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;

import com.google.common.base.Optional;
import net.minecraft.block.Block;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.ResourceLocation;

/**
 * Stable, registry-order independent names for block states: {@code minecraft:stone[variant=granite]}.
 *
 * <p>Numeric block ids move when mods are added or removed, metadata values are a block's own business,
 * and 1.12 state ids are {@code (block id, meta)} pairs, so two states can share one id. A name built
 * from the block's registry name and its full property list survives all three problems, which is what
 * the section palette stores.
 *
 * <p>Properties are written sorted by name so the same state always produces the same string.
 * Reading is deliberately forgiving - an unknown block, property or value is skipped rather than
 * throwing - so a chunk written before a mod changed still loads as much as it can.
 */
public final class BlockStateNames
{
    /** Written for state that has no registry name; reading it back deliberately fails. */
    public static final String UNRESOLVABLE = "outlands:unresolvable";

    private BlockStateNames()
    {
    }

    public static String toName(IBlockState state)
    {
        Block block = state.getBlock();
        ResourceLocation key = Block.REGISTRY.getNameForObject(block);

        // Block.REGISTRY is defaulted to air, so an unregistered block answers with its key: check the round trip.
        if (key == null || Block.REGISTRY.getObject(key) != block)
        {
            // Nothing stable to write. An unresolvable name is better than a plausible-but-wrong one:
            // the reader then falls back to the numeric palette entry.
            return UNRESOLVABLE;
        }

        StringBuilder name = new StringBuilder(key.toString());

        List<String> properties = new ArrayList<>();
        for (IProperty<?> property : block.getBlockState().getProperties())
        {
            // A property that cannot name its own value is dropped; the rest of the state still reads back.
            try
            {
                properties.add(property.getName() + "=" + rawValueName(property, state));
            }
            catch (RuntimeException ignored)
            {
            }
        }

        if (!properties.isEmpty())
        {
            Collections.sort(properties);
            name.append('[').append(String.join(",", properties)).append(']');
        }

        return name.toString();
    }

    /**
     * @return the state this name describes, or null when the block itself is gone.
     */
    @Nullable
    public static IBlockState fromName(String name)
    {
        int bracket = name.indexOf('[');
        Block block = Block.getBlockFromName(bracket < 0 ? name : name.substring(0, bracket));

        if (block == null)
        {
            return null;
        }

        IBlockState state = block.getDefaultState();

        if (bracket < 0 || !name.endsWith("]"))
        {
            return state;
        }

        String body = name.substring(bracket + 1, name.length() - 1);
        if (body.isEmpty())
        {
            return state;
        }

        for (String pair : body.split(","))
        {
            int separator = pair.indexOf('=');
            if (separator <= 0)
            {
                return null;
            }

            IProperty<?> property = findProperty(block, pair.substring(0, separator));
            if (property == null)
            {
                continue;
            }

            IBlockState updated;
            try
            {
                updated = rawWithProperty(state, property, pair.substring(separator + 1));
            }
            catch (RuntimeException ignored)
            {
                updated = null;
            }

            if (updated != null)
            {
                state = updated;
            }
        }

        return state;
    }

    @Nullable
    public static IProperty<?> findProperty(Block block, String name)
    {
        for (IProperty<?> property : block.getBlockState().getProperties())
        {
            if (property.getName().equals(name))
            {
                return property;
            }
        }
        return null;
    }

    private static <T extends Comparable<T>> String valueName(IProperty<T> property, IBlockState state)
    {
        return property.getName(state.getValue(property));
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static String rawValueName(IProperty<?> property, IBlockState state)
    {
        return valueName((IProperty)property, state);
    }

    @Nullable
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static IBlockState rawWithProperty(IBlockState state, IProperty<?> property, String value)
    {
        Optional<?> parsed = property.parseValue(value);
        return parsed.isPresent() ? withProperty(state, (IProperty)property, (Comparable)parsed.get()) : null;
    }

    private static <T extends Comparable<T>> IBlockState withProperty(IBlockState state, IProperty<T> property, T value)
    {
        return state.withProperty(property, value);
    }
}
