package net.minecraftforge.lwjgl.shim;

import java.util.Set;

/**
 * One set of call-site rewrites that routes vanilla's GL entry points to a shim facade.
 *
 * <p><b>Why a ruleset and not a runtime switch.</b> Call sites are rewritten once, when a class is loaded.
 * The choice of ruleset therefore has to be made before the first affected class loads, and cannot change
 * afterwards: switching mid-load would rewrite some classes one way and the rest another, which is a
 * non-reproducible failure. The set is registered early (coremod stage) and frozen on first use.</p>
 *
 * <p><b>RETARGET is owner-only.</b> An entry may change the owner of a call but not its name or descriptor.
 * Keeping the descriptor identical means the existing stack map frames stay valid, so no
 * {@code COMPUTE_FRAMES} pass is needed and no argument recipe is involved. Measured: a retargeted class
 * still passes {@code BasicVerifier} and {@code CheckClassAdapter}.</p>
 *
 * @see ShimRegistry
 */
public interface ShimRewriteRules {

    /** Stable id, e.g. {@code "outlands-vulkan"}. Used in configuration and logs. */
    String id();

    /**
     * Whether this ruleset applies to the given environment. Called exactly once, when the ruleset is
     * chosen. Must not have side effects.
     */
    boolean appliesTo(ShimEnvironment env);

    /** The call sites to rewrite. Consulted after the five existing tables, never before. */
    java.util.List<RetargetEntry> entries();

    /**
     * Internal-name prefixes whose classes are <b>not</b> rewritten.
     *
     * <p><b>Not optional.</b> A shim provider's own renderer has to keep calling real GL; without an
     * exemption the rewrite would send it to the shim and back to itself. The failure mode is a load-time
     * {@code NoSuchMethodError} or unbounded recursion, both hard to attribute. The source classifier cannot
     * answer this question — it only distinguishes modern from legacy jars — so the provider has to state
     * its own classes.</p>
     */
    Set<String> exemptPrefixes();
}
