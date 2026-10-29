package com.cleanroommc.loader;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

/**
 * A registry that is writable while the loader is discovering mods and read-only afterwards.
 *
 * <p>Exists to replace two older shapes that no longer hold up: an enum used as a registry (its keys are fixed at
 * compile time so mods cannot add any, and the ordinal invites being persisted instead of the name) and a plain map
 * read on a hot path (hashing plus string comparison per lookup, allocation per iteration).
 *
 * <p>The intended lifecycle is register, freeze, index:
 * <ol>
 *   <li>callers {@link #register} during discovery;</li>
 *   <li>{@link #freeze()} once, when discovery ends - it drops the id index;</li>
 *   <li>hot code resolves a slot before the freeze and keeps the {@code int}, then reads with {@link #get(int)}.</li>
 * </ol>
 *
 * <p>{@link #slotOf(String)} keeps working after the freeze but degrades to a linear scan over a small array. That is
 * intentional: it is a cold path (a one-time resolution, an error message), and keeping an index alive would defeat
 * the point of freezing.
 *
 * <p>Slots are assigned in registration order and are <b>not</b> stable across launches; never persist one. The id is
 * the only thing that may be written to a file, a log line or a GUI.
 *
 * @param <T> registered value type
 */
public final class Registry<T> {

    private final String name;
    private final Class<T> type;

    /** Registration-time id index; {@code null} once frozen. */
    @Nullable
    private Map<String, Integer> index = new HashMap<>();
    private String[] ids = new String[8];
    private Object[] slots = new Object[8];
    private int size;
    private boolean frozen;

    public Registry(String name, Class<T> type) {
        this.name = name;
        this.type = type;
    }

    /**
     * Registers {@code value} under {@code id} and returns its slot.
     *
     * @throws IllegalStateException    if the registry is already frozen
     * @throws IllegalArgumentException if the id is null or blank, already taken, or the value is of the wrong type
     */
    public int register(String id, T value) {
        if (frozen) {
            throw new IllegalStateException("Cannot register '" + id + "' in " + name + ": it was frozen after discovery");
        }
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Cannot register a " + type.getSimpleName() + " in " + name + " under a blank id");
        }
        if (!type.isInstance(value)) {
            throw new IllegalArgumentException("Cannot register " + (value == null ? "null" : value.getClass().getName())
                    + " in " + name + ", expected " + type.getName());
        }
        Integer existing = index.get(id);
        if (existing != null) {
            // The id is the only key a user or a log reader can use, so a duplicate is a conflict between two
            // declarations rather than a value to overwrite. Callers are expected to report both sources.
            throw new IllegalArgumentException("Duplicate id '" + id + "' in " + name + ": already registered as "
                    + slots[existing].getClass().getName());
        }

        if (size == slots.length) {
            int grown = size * 2;
            slots = Arrays.copyOf(slots, grown);
            ids = Arrays.copyOf(ids, grown);
        }
        slots[size] = value;
        ids[size] = id;
        index.put(id, size);
        return size++;
    }

    /** Closes the registry: further registrations fail, lookups and iteration keep working. Idempotent. */
    public void freeze() {
        if (frozen) {
            return;
        }
        frozen = true;
        index = null;
    }

    public boolean isFrozen() {
        return frozen;
    }

    public int size() {
        return size;
    }

    /** @return the value registered in {@code slot}, or {@code null} when the slot is out of range */
    @Nullable
    @SuppressWarnings("unchecked")
    public T get(int slot) {
        if (slot < 0 || slot >= size) {
            return null;
        }
        return (T) slots[slot];
    }

    /**
     * Resolves an id to its slot, or {@code -1} when unknown.
     *
     * <p>Cold path. O(1) before {@link #freeze()}, a scan over the live slots afterwards.
     */
    public int slotOf(@Nullable String id) {
        if (id == null) {
            return -1;
        }
        if (index != null) {
            Integer slot = index.get(id);
            return slot == null ? -1 : slot;
        }
        for (int slot = 0; slot < size; slot++) {
            if (ids[slot].equals(id)) {
                return slot;
            }
        }
        return -1;
    }

    /** @return the id registered for {@code slot}, or {@code null} when the slot is out of range */
    @Nullable
    public String idOf(int slot) {
        return slot < 0 || slot >= size ? null : ids[slot];
    }

    /** @return every registered id, in registration order */
    public List<String> ids() {
        return Collections.unmodifiableList(Arrays.asList(Arrays.copyOf(ids, size)));
    }

    /**
     * Formats the registered ids for an error message: what <em>is</em> available is more useful to a user than what
     * they asked for, which is usually a typo or a missing provider.
     */
    public String describeContents() {
        if (size == 0) {
            return "none";
        }
        StringBuilder result = new StringBuilder();
        for (int slot = 0; slot < size; slot++) {
            if (slot > 0) {
                result.append(", ");
            }
            result.append(ids[slot]);
        }
        return result.toString();
    }

    @Override
    public String toString() {
        return name + "[" + size + (frozen ? ", frozen" : "") + "]";
    }
}
