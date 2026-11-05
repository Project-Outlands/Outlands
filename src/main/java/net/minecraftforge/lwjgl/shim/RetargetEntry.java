package net.minecraftforge.lwjgl.shim;

/**
 * A single call-site retarget.
 *
 * <p>Only the owner changes. {@code name} and {@code desc} are carried so the entry can be matched against
 * a call site and so the intent is readable, but the rewrite must leave them untouched.</p>
 *
 * @param fromOwner internal name of the class the call currently targets, e.g. {@code org/lwjgl/opengl/GL11}
 * @param name      method name, unchanged by the rewrite
 * @param desc      method descriptor, unchanged by the rewrite
 * @param toOwner   internal name of the facade the call should target
 */
public record RetargetEntry(String fromOwner, String name, String desc, String toOwner) {

    public RetargetEntry {
        requireInternalName(fromOwner, "fromOwner");
        requireInternalName(toOwner, "toOwner");
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("name must not be empty");
        }
        if (desc == null || desc.isEmpty() || desc.charAt(0) != '(') {
            throw new IllegalArgumentException("desc must be a method descriptor: " + desc);
        }
        if (fromOwner.equals(toOwner)) {
            throw new IllegalArgumentException("retarget to the same owner is a no-op: " + fromOwner);
        }
    }

    private static void requireInternalName(String value, String what) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(what + " must not be empty");
        }
        if (value.indexOf('.') >= 0) {
            throw new IllegalArgumentException(what + " must be an internal name (slashes, not dots): " + value);
        }
    }
}
