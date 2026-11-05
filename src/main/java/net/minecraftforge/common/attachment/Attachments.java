package net.minecraftforge.common.attachment;

import org.jspecify.annotations.Nullable;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Per-host side data, replacing the "mixin a field into {@code WorldClient}" pattern.
 *
 * <p><b>Why.</b> A renderer that wants to keep a tracker next to a world currently mixes a field into
 * {@code WorldClient} and a getter interface the world is cast to. That is one mixin per (renderer, host)
 * pair, and it fails with a {@code ClassCastException} when the cast is reached on a world that was never
 * mixed. An attachment table turns the same thing into a lookup that either hits or misses.</p>
 *
 * <p><b>Host identity is reference identity.</b> {@code World} and {@code Chunk} do not override
 * {@code equals}, but relying on that would be fragile, so this table keys on identity explicitly.</p>
 *
 * <p><b>Lifetime.</b> Entries live until {@link #purge(Object)}. Per-world and per-chunk hosts must be
 * purged from {@link net.minecraftforge.client.event.ChunkLifecycle} listeners; nothing else knows when a host dies.</p>
 *
 * <p><b>Threading.</b> Writes are main-thread only. Reads may happen on chunk build threads, so a value that
 * is published must be safely publishable (immutable, or reached through a volatile field).</p>
 */
public final class Attachments {

    private static final Map<Object, Map<AttachmentKey<?>, Object>> BY_HOST = new IdentityHashMap<>();

    private Attachments() {}

    /**
     * Returns the value for a key, creating and storing it on first use.
     *
     * @throws IllegalStateException if a value of an incompatible type is already present
     */
    public static <T> T getOrCreate(Object host, AttachmentKey<T> key, Supplier<T> factory) {
        requireNonNull(host, key, factory);
        Map<AttachmentKey<?>, Object> slots = BY_HOST.get(host);
        if (slots != null) {
            Object existing = slots.get(key);
            if (existing != null) {
                return key.cast(existing);
            }
        } else {
            slots = new java.util.HashMap<>();
            BY_HOST.put(host, slots);
        }
        T created = factory.get();
        if (created == null) {
            throw new IllegalStateException("attachment factory returned null for " + key);
        }
        slots.put(key, created);
        return created;
    }

    /** Returns the value for a key, or {@code null} when absent. */
    public static <T> @Nullable T getIfPresent(Object host, AttachmentKey<T> key) {
        requireNonNull(host, key, null);
        Map<AttachmentKey<?>, Object> slots = BY_HOST.get(host);
        if (slots == null) {
            return null;
        }
        Object existing = slots.get(key);
        return existing == null ? null : key.cast(existing);
    }

    /** Stores a value. */
    public static <T> void put(Object host, AttachmentKey<T> key, T value) {
        requireNonNull(host, key, value);
        BY_HOST.computeIfAbsent(host, ignored -> new java.util.HashMap<>()).put(key, value);
    }

    /** Removes one slot. Returns whether it was present. */
    public static boolean remove(Object host, AttachmentKey<?> key) {
        requireNonNull(host, key, null);
        Map<AttachmentKey<?>, Object> slots = BY_HOST.get(host);
        if (slots == null) {
            return false;
        }
        boolean removed = slots.remove(key) != null;
        if (slots.isEmpty()) {
            BY_HOST.remove(host);
        }
        return removed;
    }

    /**
     * Drops every attachment of a host. Called from lifecycle cleanup; a caller should not need this
     * directly, but it is public so a host with no lifecycle event can still be cleaned up.
     */
    public static void purge(Object host) {
        if (host != null) {
            BY_HOST.remove(host);
        }
    }

    /** Diagnostics only. */
    public static Map<AttachmentKey<?>, Object> snapshot(Object host) {
        Map<AttachmentKey<?>, Object> slots = BY_HOST.get(host);
        return slots == null ? Collections.emptyMap() : Collections.unmodifiableMap(slots);
    }

    private static void requireNonNull(Object host, AttachmentKey<?> key, @Nullable Object value) {
        if (host == null) {
            throw new IllegalArgumentException("host must not be null");
        }
        if (key == null) {
            throw new IllegalArgumentException("key must not be null");
        }
    }
}
