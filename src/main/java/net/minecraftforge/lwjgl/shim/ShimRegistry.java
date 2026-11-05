package net.minecraftforge.lwjgl.shim;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Registry and selector for {@link ShimRewriteRules}.
 *
 * <p>Rulesets are registered during the coremod stage — plain code running before any mod class loads — and
 * resolved once, on the first class that reaches the rewriter. After that the choice is frozen and asserted
 * to stay the same.</p>
 *
 * <p><b>Why freezing is enforced rather than documented.</b> A mid-load switch would rewrite part of the
 * process one way and part another. That failure is silent and not reproducible, so it is turned into an
 * immediate error instead.</p>
 */
public final class ShimRegistry {

    private static final Logger LOGGER = LogManager.getLogger("Outlands|shim");

    private static final List<ShimRewriteRules> RULESETS = new ArrayList<>();

    /** Package prefixes that always keep real GL, registered by the renderers that own them. */
    private static final List<String> EXEMPT_PREFIXES = new ArrayList<>();

    /** Frozen selection. Empty list means "no rewriting", which is the default and the vanilla behaviour. */
    private static volatile @Nullable Selection selection;

    private ShimRegistry() {}

    /**
     * Registers a ruleset. Must be called before the first rewrite; a late call is logged and ignored because
     * it can no longer affect already-loaded classes.
     */
    public static void register(ShimRewriteRules ruleset) {
        if (ruleset == null) {
            throw new IllegalArgumentException("ruleset must not be null");
        }
        synchronized (RULESETS) {
            if (selection != null) {
                LOGGER.warn("Ignoring late shim ruleset '{}': selection already frozen", ruleset.id());
                return;
            }
            for (ShimRewriteRules existing : RULESETS) {
                if (existing.id().equals(ruleset.id())) {
                    throw new IllegalArgumentException("duplicate shim ruleset id: " + ruleset.id());
                }
            }
            RULESETS.add(ruleset);
        }
    }

    /**
     * Declares that a renderer issues real GL and must not be retargeted.
     *
     * <p><b>Why this is not the ruleset's job.</b> A ruleset is written by the shim provider and lists the
     * classes it knows about. A third-party renderer cannot edit that list, and it should not have to: the fact
     * "this package draws through real GL" is owned by the renderer, not by whoever wrote the shim. Without a
     * way to state it, the rewrite reaches a renderer's own GL calls, and the failure shows up as a load-time
     * {@code NoSuchMethodError} or unbounded recursion.</p>
     *
     * <p>Exemptions are unioned with the selected ruleset's own list, so a renderer does not have to know which
     * shim is in use. Registering after the freeze is rejected rather than ignored: by then classes have already
     * been rewritten and the exemption would be silently ineffective.</p>
     *
     * @param packagePrefix an internal-name prefix, e.g. {@code "com/dhj/actinium/render/"}
     * @throws IllegalStateException    if the selection is already frozen
     * @throws IllegalArgumentException if the prefix is blank or already registered
     */
    public static void exempt(String packagePrefix) {
        if (packagePrefix == null || packagePrefix.isBlank()) {
            throw new IllegalArgumentException("exempt prefix must not be blank");
        }
        if (packagePrefix.indexOf('.') >= 0) {
            throw new IllegalArgumentException(
                    "exempt prefix must be an internal name (slashes, not dots): " + packagePrefix);
        }
        synchronized (RULESETS) {
            if (selection != null) {
                throw new IllegalStateException("Cannot exempt '" + packagePrefix
                        + "': rewriting is already frozen, so classes have already been rewritten");
            }
            if (EXEMPT_PREFIXES.contains(packagePrefix)) {
                throw new IllegalArgumentException("already exempt: " + packagePrefix);
            }
            EXEMPT_PREFIXES.add(packagePrefix);
        }
    }

    /**
     * Resolves the ruleset for an environment, freezing the result. Idempotent; the first call wins and every
     * later call returns the same selection regardless of the environment passed.
     */
    public static Selection select(ShimEnvironment env) {
        Selection current = selection;
        if (current != null) {
            return current;
        }
        synchronized (RULESETS) {
            if (selection != null) {
                return selection;
            }
            Selection resolved = resolve(env).withExtraExemptions(List.copyOf(EXEMPT_PREFIXES));
            selection = resolved;
            if (resolved.isEmpty()) {
                LOGGER.info("Shim rewriting: disabled (no ruleset applies)");
            } else {
                LOGGER.info("Shim rewriting: ruleset '{}', {} call sites", resolved.rulesetId(), resolved.entries().size());
            }
            return resolved;
        }
    }

    /** The frozen selection, or {@code null} if nothing has been selected yet. */
    public static @Nullable Selection selection() {
        return selection;
    }

    private static Selection resolve(ShimEnvironment env) {
        for (ShimRewriteRules ruleset : RULESETS) {
            boolean applies;
            try {
                applies = ruleset.appliesTo(env);
            } catch (Throwable t) {
                LOGGER.warn("Shim ruleset '{}' failed its applicability check; skipping", ruleset.id(), t);
                continue;
            }
            if (applies) {
                return Selection.of(ruleset);
            }
        }
        return Selection.EMPTY;
    }

    /** Test hook: clears the registry, the exemptions and the selection. */
    static void resetForTests() {
        synchronized (RULESETS) {
            RULESETS.clear();
            EXEMPT_PREFIXES.clear();
            selection = null;
        }
    }

    /**
     * An immutable, frozen ruleset selection with its entries indexed for lookup.
     */
    public static final class Selection {

        static final Selection EMPTY = new Selection(null, List.of(), Set.of());

        private final @Nullable String rulesetId;
        private final List<RetargetEntry> entries;
        private final Set<String> exemptPrefixes;
        private final Map<String, String> byCallSite;

        private Selection(@Nullable String rulesetId, List<RetargetEntry> entries, Set<String> exemptPrefixes) {
            this.rulesetId = rulesetId;
            this.entries = entries;
            this.exemptPrefixes = exemptPrefixes;
            Map<String, String> index = new HashMap<>(entries.size() * 2);
            for (RetargetEntry entry : entries) {
                index.put(key(entry.fromOwner(), entry.name(), entry.desc()), entry.toOwner());
            }
            this.byCallSite = Map.copyOf(index);
        }

        private static Selection of(ShimRewriteRules ruleset) {
            List<RetargetEntry> entries = List.copyOf(ruleset.entries());
            Set<String> exempt = Set.copyOf(new HashSet<>(ruleset.exemptPrefixes()));
            return new Selection(ruleset.id(), entries, exempt);
        }

        /** Returns a copy whose exemptions also cover {@code extra}, which is how registry-level exemptions join. */
        private Selection withExtraExemptions(List<String> extra) {
            if (extra.isEmpty()) {
                return this;
            }
            Set<String> merged = new HashSet<>(exemptPrefixes);
            merged.addAll(extra);
            return new Selection(rulesetId, entries, Set.copyOf(merged));
        }

        public boolean isEmpty() {
            return rulesetId == null;
        }

        public @Nullable String rulesetId() {
            return rulesetId;
        }

        public List<RetargetEntry> entries() {
            return entries;
        }

        /**
         * The new owner for a call site, or {@code null} when this selection does not rewrite it.
         */
        public @Nullable String retarget(String owner, String name, String desc) {
            return byCallSite.get(key(owner, name, desc));
        }

        /** Whether a class's call sites are left alone. */
        public boolean isExempt(String internalName) {
            for (String prefix : exemptPrefixes) {
                if (internalName.startsWith(prefix)) {
                    return true;
                }
            }
            return false;
        }

        private static String key(String owner, String name, String desc) {
            return owner + '\0' + name + '\0' + desc;
        }
    }
}
