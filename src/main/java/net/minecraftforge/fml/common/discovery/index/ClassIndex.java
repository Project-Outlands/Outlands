package net.minecraftforge.fml.common.discovery.index;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

import net.minecraftforge.fml.common.discovery.cache.ClassScanRecord;
import net.minecraftforge.fml.common.discovery.cache.JarScanRecord;

/**
 * The classes of one archive, sorted by name so that a lookup is a binary search and a package query is a
 * contiguous range: what a script compiler needs to answer "what shape does this class have" without loading it.
 *
 * <p>Built from the scan discovery already took, so it costs a sort and no parsing. Names are internal form
 * ({@code a/b/C}) and the super type chain is resolved inside this index, which is where a jar's own hierarchy
 * lives; a super type from elsewhere is reported by name and not followed.
 *
 * <p>Immutable after construction.
 */
public final class ClassIndex {

    /** Sorted by {@link ClassScanRecord#internalName()}. */
    private final ClassScanRecord[] classes;

    private static final Comparator<ClassScanRecord> BY_NAME =
            Comparator.comparing(ClassScanRecord::internalName);

    private ClassIndex(ClassScanRecord[] sorted) {
        this.classes = sorted;
    }

    /** @param records the classes of one archive, in any order */
    public static ClassIndex of(ClassScanRecord[] records) {
        final ClassScanRecord[] sorted = Arrays.copyOf(records, records.length);
        Arrays.sort(sorted, BY_NAME);
        return new ClassIndex(sorted);
    }

    public static ClassIndex of(JarScanRecord scan) {
        return of(scan.records());
    }

    public int size() {
        return classes.length;
    }

    /** @param internalName a name such as {@code net/minecraft/world/World} */
    @Nullable
    public ClassScanRecord byName(String internalName) {
        final int position = find(internalName);
        return position < 0 ? null : classes[position];
    }

    /**
     * @param packagePrefix a package in internal form ending with a slash, or {@code ""} for the default package;
     *                      only the classes directly in it are returned
     */
    public List<ClassScanRecord> inPackage(String packagePrefix) {
        final List<ClassScanRecord> result = new ArrayList<>();
        for (int position = lowerBound(packagePrefix);
                position < classes.length && classes[position].internalName().startsWith(packagePrefix);
                position++) {
            if (classes[position].internalName().indexOf('/', packagePrefix.length()) < 0) {
                result.add(classes[position]);
            }
        }
        return result;
    }

    /** @return the internal names of the classes directly in {@code packagePrefix} */
    public List<String> namesInPackage(String packagePrefix) {
        final List<String> result = new ArrayList<>();
        for (ClassScanRecord record : inPackage(packagePrefix)) {
            result.add(record.internalName());
        }
        return result;
    }

    /** @return the classes of this archive whose name starts with {@code namePrefix} */
    public List<ClassScanRecord> withPrefix(String namePrefix) {
        final List<ClassScanRecord> result = new ArrayList<>();
        for (int position = lowerBound(namePrefix);
                position < classes.length && classes[position].internalName().startsWith(namePrefix);
                position++) {
            result.add(classes[position]);
        }
        return result;
    }

    public List<ClassScanRecord> all() {
        return List.of(classes);
    }

    /**
     * @return the ancestors of {@code internalName} that this archive knows, nearest first, ending with the first
     *         one it does not know (so a caller can follow the chain elsewhere); empty when the class is unknown here
     */
    public List<String> supertypes(String internalName) {
        final ClassScanRecord root = byName(internalName);
        if (root == null) {
            return List.of();
        }
        final List<String> result = new ArrayList<>();
        final Deque<String> pending = new ArrayDeque<>();
        final Set<String> seen = new HashSet<>();
        seen.add(internalName);
        enqueueParents(root, pending, seen);
        while (!pending.isEmpty()) {
            final String current = pending.removeFirst();
            result.add(current);
            final ClassScanRecord record = byName(current);
            if (record == null) {
                continue; // defined elsewhere, or not a class of this archive: report, do not follow
            }
            enqueueParents(record, pending, seen);
        }
        return result;
    }

    private static void enqueueParents(ClassScanRecord record, Deque<String> pending, Set<String> seen) {
        if (record.superName() != null && seen.add(record.superName())) {
            pending.addLast(record.superName());
        }
        for (String intf : record.interfaces()) {
            if (seen.add(intf)) {
                pending.addLast(intf);
            }
        }
    }

    /** @return whether {@code type} is {@code other} or descends from it, as far as this archive can tell */
    public boolean isSameOrSubtypeOf(String type, String other) {
        return type.equals(other) || supertypes(type).contains(other);
    }

    /** @return the position of {@code internalName}, or -1 */
    private int find(String internalName) {
        int low = 0;
        int high = classes.length - 1;
        while (low <= high) {
            final int middle = (low + high) >>> 1;
            final int order = classes[middle].internalName().compareTo(internalName);
            if (order < 0) {
                low = middle + 1;
            } else if (order > 0) {
                high = middle - 1;
            } else {
                return middle;
            }
        }
        return -1;
    }

    /** @return the first position whose name is not less than {@code prefix} */
    private int lowerBound(String prefix) {
        int low = 0;
        int high = classes.length;
        while (low < high) {
            final int middle = (low + high) >>> 1;
            if (classes[middle].internalName().compareTo(prefix) < 0) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        return low;
    }
}
