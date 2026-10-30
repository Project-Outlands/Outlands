/*
 * Minecraft Forge
 * Copyright (c) 2016-2020.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation version 2.1.
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

package net.minecraftforge.fml.common.discovery;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.ToIntFunction;

/**
 * Decides the order jars are opened when coremods are discovered.
 *
 * <p>This is the only order the {@code loadOrder} declaration affects. The candidate list, the classpath and the order
 * mixin containers are submitted are all decided elsewhere and stay untouched, so a pack whose jars declare nothing
 * keeps exactly the order it has today - and that order matters for reasons no later stage can repair: a coremod's
 * plugin class is loaded the moment it is discovered, and classes cannot be transformed after the fact, so "who is
 * opened first" is what decides which classes a transformer can still affect.
 *
 * <p>Lives outside {@link ModDiscoverer} because that class discovers mods from its constructor, which makes anything
 * on it unreachable without a game; the rule here is plain data and needs to be checkable on its own.
 */
public final class CoremodLoadOrder {

    private CoremodLoadOrder() {
    }

    /**
     * @param rankOf declared position of a jar, {@code 0} when it declares none
     * @return the jars in the order they should be opened for coremod discovery
     */
    public static List<DiscoveredMod> sort(Collection<DiscoveredMod> discovered, ToIntFunction<File> rankOf) {
        List<DiscoveredMod> ordered = new ArrayList<>(discovered);
        ordered.sort(Comparator
                .comparingInt((DiscoveredMod mod) -> rankOf.applyAsInt(mod.file()))
                // The rule the rest of discovery already uses, so an undeclared jar cannot move relative to its peers.
                .thenComparing(mod -> mod.file().getName().toLowerCase(Locale.ENGLISH))
                // Two jars may share a name in different directories, where filename comparison cannot separate them
                // and the filesystem's own order is not defined; pin it instead of leaving it to the machine.
                .thenComparing(mod -> mod.file().getAbsolutePath()));
        return ordered;
    }
}
