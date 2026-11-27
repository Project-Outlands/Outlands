package net.minecraftforge.fml.common.discovery.index;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nullable;

/**
 * What a mod that wants the index as a file asks the loader for.
 *
 * <p>There is no loader command and no metadata flag: the consumer is the caller, which is why a script mod's own
 * command (or button, or GUI) is the natural place to trigger this. The call is asynchronous, idempotent and never
 * throws; a caller reports {@link Report#error()} and nothing else.
 *
 * <p>Nothing here is on the startup path. Discovery writes the class records on its own, so a scope is only needed for
 * data that costs a pass of its own.
 */
public interface IndexExport {

    /** The kinds of data that can be written on request. */
    enum Scope {
        /** Generic signatures, nested class identity, member signatures, access transformer rules. */
        DETAIL,
        /** Item, block and fluid registries, as the running game has them. Not implemented yet. */
        REGISTRY,
        /** The SRG to MCP name table, as the user's overlay has it. Not implemented yet. */
        NAMES
    }

    /**
     * @param builtAt when the files were written
     * @param sources archives named in the manifest
     * @param files   files written or refreshed
     * @param bytes   their total size
     * @param counts  how many records each scope produced
     * @param error   what went wrong, or null when it did not
     */
    record Report(Instant builtAt, int sources, int files, long bytes, Map<Scope, Integer> counts, @Nullable String error) {}

    /** Runs once at a time: a second call while one is running joins it. */
    CompletableFuture<Report> dump(EnumSet<Scope> scopes);
}
