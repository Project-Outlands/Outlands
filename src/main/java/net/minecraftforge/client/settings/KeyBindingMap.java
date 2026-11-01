/*
 * Minecraft Forge
 * Copyright (c) 2016-2020.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation version 2.1
 * of the License.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */

package net.minecraftforge.client.settings;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.IntHashMap;
import org.lwjgl.input.InputSnapshot;

public class KeyBindingMap {
    private final IntHashMap<Collection<KeyBinding>> map = new IntHashMap<>();

    @Nullable
    @Deprecated // Outlands: Use lookupActives
    public KeyBinding lookupActive(int keyCode) {
        final Set<KeyBinding> bindings = lookupActives(keyCode);
        return bindings.isEmpty() ? null : bindings.iterator().next();
    }

    public Set<KeyBinding> lookupActives(int keyCode) {
        return lookupActives(keyCode, InputSnapshot.current());
    }

    /** Returns the most specific matching layer for one trigger event. */
    public Set<KeyBinding> lookupActives(int keyCode, InputSnapshot snapshot) {
        final Collection<KeyBinding> candidates = map.lookup(keyCode);
        if (candidates == null) {
            return Collections.emptySet();
        }
        int largestCombination = 0;
        final Set<KeyBinding> matches = new LinkedHashSet<>();
        for (KeyBinding binding : candidates) {
            if (!binding.isActiveAndMatches(keyCode, snapshot)) {
                continue;
            }
            final int combinationSize = binding.getKeyCombination().size();
            if (combinationSize > largestCombination) {
                matches.clear();
                largestCombination = combinationSize;
            }
            if (combinationSize == largestCombination) {
                matches.add(binding);
            }
        }
        if (matches.size() > 1 && !net.minecraftforge.common.ForgeEarlyConfig.INPUT_KEYBIND_ALL_MATCHING)
        {
            return Collections.singleton(matches.iterator().next());
        }
        return matches;
    }

    public List<KeyBinding> lookupAll(int keyCode) {
        final Collection<KeyBinding> bindings = map.lookup(keyCode);
        return bindings == null ? Collections.emptyList() : new ArrayList<>(bindings);
    }

    public void addKey(int keyCode, KeyBinding keyBinding) {
        Collection<KeyBinding> bindingsForKey = map.lookup(keyCode);
        if (bindingsForKey == null) {
            bindingsForKey = new ArrayList<>();
            map.addKey(keyCode, bindingsForKey);
        }
        bindingsForKey.add(keyBinding);
    }

    public void removeKey(KeyBinding keyBinding) {
        final int keyCode = keyBinding.getKeyCode();
        final Collection<KeyBinding> bindingsForKey = map.lookup(keyCode);
        if (bindingsForKey != null) {
            bindingsForKey.remove(keyBinding);
            if (bindingsForKey.isEmpty()) {
                map.removeObject(keyCode);
            }
        }
    }

    public void clearMap() {
        map.clearMap();
    }
}
