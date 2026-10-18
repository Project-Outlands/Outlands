package com.cleanroommc.cleanmix.service;

import net.minecraft.launchwrapper.Launch;
import org.spongepowered.asm.service.IClassTracker;
import top.outlands.foundation.boot.ActualClassLoader;
import top.outlands.foundation.boot.PrefixMatcher;

final class FoundationClassTracker implements IClassTracker {

    private final ActualClassLoader classLoader;

    FoundationClassTracker() {
        this.classLoader = Launch.classLoader;
    }

    @Override
    public boolean isClassLoaded(String name) {
        return this.classLoader.isClassLoaded(name);
    }

    @Override
    public String getClassRestrictions(String className) {
        String restrictions = "";
        if (ActualClassLoader.classLoaderExceptions.matches(className)) {
            restrictions = "PACKAGE_CLASSLOADER_EXCLUSION";
        }
        if (ActualClassLoader.transformerExceptions.matches(className)) {
            restrictions = (!restrictions.isEmpty() ? restrictions + "," : "") + "PACKAGE_TRANSFORMER_EXCLUSION";
        }
        return restrictions;
    }

    @Override
    public void registerInvalidClass(String name) {
        this.classLoader.registerInvalidClass(name);
    }

    /** Whether the name or transformedName is excluded from the classloader or from the transformer chain. */
    boolean isClassExcluded(String name, String transformedName) {
        return isExcluded(ActualClassLoader.classLoaderExceptions, name, transformedName)
                || isExcluded(ActualClassLoader.transformerExceptions, name, transformedName);
    }

    private static boolean isExcluded(PrefixMatcher matcher, String name, String transformedName) {
        return isExcluded(matcher, name) || (transformedName != null && isExcluded(matcher, transformedName));
    }

    private static boolean isExcluded(PrefixMatcher matcher, String name) {
        return matcher.matches(name);
    }

}
