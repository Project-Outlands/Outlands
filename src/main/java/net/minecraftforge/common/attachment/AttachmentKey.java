package net.minecraftforge.common.attachment;

/**
 * Identity of one attachment slot.
 *
 * <p>Keys compare by <b>reference</b>, so each declaration site owns exactly one slot. Declare them as
 * {@code public static final} fields and never construct a second key with the same id.</p>
 *
 * @param <T> the attached value type
 */
public final class AttachmentKey<T> {

    private final String id;

    private AttachmentKey(String id) {
        this.id = id;
    }

    /**
     * @param id a stable, human-readable name used in diagnostics; not part of the identity
     */
    public static <T> AttachmentKey<T> of(String id) {
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("attachment key id must not be empty");
        }
        return new AttachmentKey<>(id);
    }

    public String id() {
        return id;
    }

    /**
     * Casts a stored value to this key's type.
     *
     * <p>The table is keyed by reference, so a value can only have been stored under this exact key and the
     * cast is safe; keeping it here means the unchecked operation lives in one place.</p>
     */
    @SuppressWarnings("unchecked")
    T cast(Object value) {
        return (T) value;
    }

    @Override
    public String toString() {
        return "AttachmentKey[" + id + ']';
    }
}
