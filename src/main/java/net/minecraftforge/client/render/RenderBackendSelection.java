package net.minecraftforge.client.render;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * The single decision point for which render backend the process runs on.
 *
 * <p><b>Timing.</b> {@link #select()} runs from the display creation path, i.e. <i>before</i> the window is
 * created. That is the earliest point at which the answer is still actionable, and the latest point at which
 * it is still possible to choose {@code SDL_WINDOW_OPENGL} instead of {@code SDL_WINDOW_VULKAN}.</p>
 *
 * <p><b>Why it never changes afterwards.</b> The window flags are fixed at creation, so a backend switch
 * later would leave the process with a window it cannot draw to. Everything downstream therefore reads the
 * value once, through {@link #get()}, and treats it as immutable.</p>
 *
 * <p><b>Fallback is total, not partial.</b> The fallback target is the vanilla renderer itself, not another
 * renderer. That means a Vulkan backend must cover <i>every</i> phase it is responsible for: there is no way
 * to draw a single phase with GL inside a {@code SDL_WINDOW_VULKAN} window. A backend that cannot cover
 * everything must therefore not be selected at all.</p>
 *
 * <p><b>Why a failed probe is survivable.</b> Probe failures are contained to the point where the backend is
 * asked to do work, not to class loading (measured: a call site whose target is missing still loads and only
 * fails on first execution). So selecting optimistically and degrading later is safe, provided the shim
 * facade keeps a real-GL implementation available — see {@code top.outlands.render.shim.ShimGl}.</p>
 */
public final class RenderBackendSelection {

    private static final Logger LOGGER = LogManager.getLogger("Outlands|render");

    /**
     * Configuration key. Kept as a system property so it can be set from the launcher before any mod loads,
     * which is the only window in which the answer can still influence the window flags.
     */
    public static final String PROPERTY = "outlands.render.backend";

    /** Candidates in descending preference. Populated by providers before {@link #select()}. */
    private static final List<Candidate> CANDIDATES = new ArrayList<>();

    private static volatile RenderBackend selected;
    private static volatile boolean selectionFrozen;

    private RenderBackendSelection() {}

    /**
     * A backend that may be selected, and the probe that decides whether it can be.
     *
     * @param backend     the backend
     * @param probe       availability check; must not create GL or Vulkan objects, only query
     * @param priority    higher wins when several candidates are available
     */
    public record Candidate(RenderBackend backend, BackendProbe probe, int priority) {}

    /** Availability check for one backend. Implementations must be side-effect free. */
    public interface BackendProbe {
        /**
         * @return whether this backend can own the frame on this machine
         */
        boolean isAvailable();
    }

    /**
     * Registers a candidate. Must be called before {@link #select()}; calls afterwards are ignored and logged,
     * because a candidate registered late cannot affect the window flags.
     */
    public static void register(Candidate candidate) {
        if (candidate == null || candidate.backend() == null) {
            throw new IllegalArgumentException("candidate and backend must not be null");
        }
        synchronized (CANDIDATES) {
            if (selectionFrozen) {
                LOGGER.warn("Ignoring late backend candidate '{}': selection already happened", candidate.backend().id());
                return;
            }
            CANDIDATES.add(candidate);
        }
    }

    /**
     * Resolves and freezes the backend. Idempotent: the first call decides, later calls return the same value.
     *
     * <p>Resolution order:</p>
     * <ol>
     *     <li>an explicit {@value #PROPERTY} value, if it names a known backend;</li>
     *     <li>otherwise the highest-priority candidate whose probe reports available;</li>
     *     <li>otherwise the vanilla renderer.</li>
     * </ol>
     */
    public static RenderBackend select() {
        RenderBackend already = selected;
        if (already != null) {
            return already;
        }
        synchronized (CANDIDATES) {
            if (selected != null) {
                return selected;
            }
            RenderBackend resolved = resolve();
            selected = resolved;
            selectionFrozen = true;
            // The table is closed at the same moment: a backend registered afterwards could not affect the window
            // flags, so accepting it would only make the registry disagree with the process.
            RenderBackends.freeze();
            LOGGER.info("Render backend: {}", resolved.id());
            return resolved;
        }
    }

    private static RenderBackend resolve() {
        String forced = System.getProperty(PROPERTY);
        if (forced != null && !forced.isEmpty()) {
            RenderBackend backend = RenderBackends.get(forced);
            if (backend == null) {
                LOGGER.warn("Unknown render backend '{}' in -D{}; falling back to {}", forced, PROPERTY,
                        RenderBackends.VANILLA_GL_ID);
                return RenderBackend.VANILLA;
            }
            if (RenderBackends.isVanilla(backend.id())) {
                return backend;
            }
            // A forced backend still has to prove it can own the frame, otherwise the window would be created
            // with flags nothing can draw to.
            Candidate candidate = find(backend);
            if (candidate == null) {
                LOGGER.warn("Render backend '{}' was forced but is not registered; falling back to {}", backend.id(),
                        RenderBackends.VANILLA_GL_ID);
                return RenderBackend.VANILLA;
            }
            if (!probe(candidate)) {
                LOGGER.warn("Render backend '{}' was forced but its probe reports unavailable; falling back to {}",
                        backend.id(), RenderBackends.VANILLA_GL_ID);
                return RenderBackend.VANILLA;
            }
            return backend;
        }

        Candidate best = null;
        for (Candidate candidate : CANDIDATES) {
            if (RenderBackends.isVanilla(candidate.backend().id())) {
                continue;
            }
            if (!probe(candidate)) {
                continue;
            }
            if (best == null || candidate.priority() > best.priority()) {
                best = candidate;
            }
        }
        return best == null ? RenderBackend.VANILLA : best.backend();
    }

    private static boolean probe(Candidate candidate) {
        try {
            return candidate.probe().isAvailable();
        } catch (Throwable t) {
            // A probe that throws is a probe that says no. Never let a probe failure abort startup.
            LOGGER.warn("Backend probe for '{}' threw; treating as unavailable", candidate.backend().id(), t);
            return false;
        }
    }

    private static Candidate find(RenderBackend backend) {
        for (Candidate candidate : CANDIDATES) {
            if (candidate.backend() == backend || candidate.backend().id().equals(backend.id())) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * The selected backend. Selects on first use if nobody called {@link #select()} yet, so call sites never
     * need a null check.
     */
    public static RenderBackend get() {
        RenderBackend backend = selected;
        return backend != null ? backend : select();
    }

    /** Whether the selection has been made. Only meaningful for diagnostics and tests. */
    public static boolean isSelected() {
        return selected != null;
    }

    /** Test hook: clears the selection. Not for production use. */
    static void resetForTests() {
        synchronized (CANDIDATES) {
            selected = null;
            selectionFrozen = false;
            CANDIDATES.clear();
        }
    }
}
