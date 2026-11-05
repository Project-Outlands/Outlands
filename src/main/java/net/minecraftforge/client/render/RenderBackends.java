package net.minecraftforge.client.render;

import net.minecraftforge.fml.common.util.SlotTable;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The registered render backends.
 *
 * <p><b>Why a table and not an enum.</b> An enum would have to name every backend up front, including the ones
 * that do not exist yet: a {@code VULKAN} constant with nothing behind it is a promise the loader cannot keep,
 * and it would appear in configuration, logs and {@code values()} as if it were real. A table holds exactly the
 * backends that were registered, and nothing else.</p>
 *
 * <p>The one exception is {@link #VANILLA_GL}, which is not an implementation but the absence of one: the
 * unmodified vanilla renderer. It is a built-in slot, so it keeps slot {@code 0} and every array indexed by
 * backend id keeps working.</p>
 *
 * <p><b>Only the id leaves the process.</b> A slot is a position in this launch's table; inserting a built-in
 * entry shifts it. Ids are what go into configuration and logs.</p>
 *
 * <p>Registration happens during the coremod stage and the table is frozen before the window is created, which
 * is the point at which the choice stops being reversible.</p>
 */
public final class RenderBackends {

    /** Stable id of the vanilla renderer. */
    public static final String VANILLA_GL_ID = "vanilla-gl";

    private static final Set<String> RESERVED_IDS = ConcurrentHashMap.newKeySet();

    /**
     * Not final: {@link #resetForTests()} has to be able to rebuild it, because {@code SlotTable} is
     * append-only and freezing is one-way. Production code never reassigns it.
     */
    private static SlotTable<RenderBackend> table = newTable();

    private static SlotTable<RenderBackend> newTable() {
        SlotTable<RenderBackend> created = new SlotTable<>("render backends", RenderBackend.class);
        // The built-in prefix is the vanilla renderer and nothing else: it is the only backend the loader itself
        // can always provide, because it is the renderer that is already there.
        created.registerBuiltin(VANILLA_GL_ID, RenderBackend.VANILLA);
        created.freezeBuiltins();
        return created;
    }

    private RenderBackends() {}

    /**
     * Registers a backend implementation.
     *
     * @param backend the backend; its {@link RenderBackend#id()} is the registration key
     * @return the slot the backend occupies
     * @throws IllegalStateException    if the table is already frozen
     * @throws IllegalArgumentException if the id is blank, already taken, or reserved
     */
    public static int register(RenderBackend backend) {
        if (backend == null) {
            throw new IllegalArgumentException("backend must not be null");
        }
        String id = backend.id();
        if (RESERVED_IDS.contains(id)) {
            throw new IllegalArgumentException("Backend id '" + id + "' is reserved");
        }
        return table.register(id, backend);
    }

    /** Closes the table. Called once the backend is chosen; later registrations fail. Idempotent. */
    public static void freeze() {
        table.freeze();
    }

    public static boolean isFrozen() {
        return table.isFrozen();
    }

    /** The backend for an id, or {@code null} when nothing is registered under it. */
    public static RenderBackend get(String id) {
        return table.get(id);
    }

    /** The vanilla backend, which is always present. */
    public static RenderBackend vanilla() {
        return RenderBackend.VANILLA;
    }

    /** Every registered id, built-ins first. */
    public static java.util.List<String> ids() {
        return table.ids();
    }

    /** @return whether {@code id} names the vanilla renderer */
    public static boolean isVanilla(String id) {
        return VANILLA_GL_ID.equals(id);
    }

    /** Test hook: rebuilds the table. Production code has no reason to call this. */
    static void resetForTests() {
        table = newTable();
    }
}
