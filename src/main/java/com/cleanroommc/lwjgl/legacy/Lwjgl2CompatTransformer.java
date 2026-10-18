package com.cleanroommc.lwjgl.legacy;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

import net.minecraft.launchwrapper.IClassTransformer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Rewrites LWJGL2 call sites found in mod classes to native LWJGL3 calls.
 * Never modifies {@code org.lwjgl.**}: missing classes are provided separately, missing members go through adapters.
 *
 * <p>Which sources may be rewritten is decided by the mod markings of the early scan, see {@link ModSourceIndex}.
 * The point is not speed: a Cleanroom mod ({@code ModType: CRL}) is written against the LWJGL3 API, so rewriting it
 * means second-guessing code that is already up to date. Everything else stays rewritten - non-mod sources included:
 * the OpenAL binding Minecraft itself ships ({@code paulscode/sound/libraries}) carries LWJGL2 call sites and only
 * works because this transformer converts them. The mode is selected by {@code -Dcrl.lwjgl2.filter}:
 * <ul>
 *   <li>{@code off} (default) - no classification at all, behave exactly like before;</li>
 *   <li>{@code measure} - transform as before, and report where the filter would have changed the outcome;</li>
 *   <li>{@code skip} - leave the classes of a Cleanroom mod alone, rewrite everything else.</li>
 * </ul>
 *
 * <p>{@code measure} answers the only question that matters for correctness: whether a Cleanroom mod is being
 * rewritten today, i.e. whether there is anything to prevent at all.
 */
public class Lwjgl2CompatTransformer implements IClassTransformer {

    private static final Logger LOGGER = LogManager.getLogger("Cleanroom|lwjgl2compat");

    private static final String MODE = System.getProperty("crl.lwjgl2.filter", "off");
    private static final boolean MEASURE = "measure".equals(MODE);
    private static final boolean SKIP = "skip".equals(MODE);
    private static final boolean CLASSIFY = MEASURE || SKIP;

    /** Log a summary at most once per this many classified classes. */
    private static final int REPORT_INTERVAL = 2000;

    /** How many distinct sources to remember per outcome, to keep the report readable. */
    private static final int SOURCE_SAMPLES = 8;

    private static final String[] EXCLUDED_PREFIXES = {
            "org/lwjgl/",
            "com/cleanroommc/lwjgl/",
            "net/minecraftforge/fml/relauncher/",
            "java/",
            "javax/",
            "jdk/",
            "sun/",
            "com/sun/",
            "org/objectweb/asm/",
    };

    /** Only used for {@link #MEASURE} and {@link #SKIP}; {@code null} while the filter is off. */
    private static final ModSourceIndex SOURCES = CLASSIFY ? new ModSourceIndex() : null;
    private static final Map<ModSourceIndex.Kind, LongAdder> SEEN = counters();
    private static final Map<ModSourceIndex.Kind, LongAdder> SKIPPED = counters();
    private static final Map<ModSourceIndex.Kind, LongAdder> REWRITTEN = counters();
    /** Classes a skip would have dropped although this transformer rewrote them; the false-negative evidence. */
    private static final Map<ModSourceIndex.Kind, LongAdder> DROPPED = counters();
    private static final Set<String> DROPPED_SOURCES = ConcurrentHashMap.newKeySet();
    private static final Set<String> CLEANROOM_REWRITTEN = ConcurrentHashMap.newKeySet();
    private static final LongAdder CLASSIFIED = new LongAdder();
    private static final AtomicInteger SINCE_REPORT = new AtomicInteger();
    private static final AtomicBoolean ANNOUNCED = new AtomicBoolean();

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (basicClass == null) {
            return null;
        }
        String dotted = transformedName != null ? transformedName : name;
        if (dotted == null || dotted.isEmpty()) {
            return basicClass;
        }
        String internal = dotted.replace('.', '/');
        if (isExcluded(internal)) {
            return basicClass;
        }

        ModSourceIndex.Kind kind = ModSourceIndex.Kind.UNCLASSIFIED;
        if (CLASSIFY) {
            if (ANNOUNCED.compareAndSet(false, true)) {
                LOGGER.info("LWJGL2 filter active in mode {}, classifying {}", MODE, internal);
            }
            kind = SOURCES.kindOf(internal);
            SEEN.get(kind).increment();
            CLASSIFIED.increment();
            if (SINCE_REPORT.incrementAndGet() >= REPORT_INTERVAL) {
                SINCE_REPORT.set(0);
                report();
            }
            if (SKIP && kind == ModSourceIndex.Kind.CLEANROOM_MOD) {
                SKIPPED.get(kind).increment();
                return basicClass;
            }
        }

        if (!ConstantPoolRefs.mentionsLwjgl(basicClass) || !ConstantPoolRefs.hasLwjgl2MemberRef(basicClass)) {
            return basicClass;
        }
        try {
            byte[] rewritten = CompatRewriter.rewrite(basicClass);
            if (rewritten == null) {
                return basicClass;
            }
            if (CLASSIFY) {
                REWRITTEN.get(kind).increment();
                if (kind == ModSourceIndex.Kind.CLEANROOM_MOD) {
                    // A Cleanroom mod class was rewritten: exactly the redundant work the filter exists to prevent.
                    // Report it immediately, one line per source, so a short run leaves evidence instead of
                    // depending on a periodic summary.
                    DROPPED.get(kind).increment();
                    String source = describeSource(internal);
                    if (DROPPED_SOURCES.size() < SOURCE_SAMPLES && DROPPED_SOURCES.add(source)) {
                        LOGGER.info("rewrote {} class {} from {}", kind, internal, source);
                    }
                    if (CLEANROOM_REWRITTEN.size() < SOURCE_SAMPLES) {
                        CLEANROOM_REWRITTEN.add(internal);
                    }
                }
            }
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("rewrote LWJGL2 call sites in {}", internal);
            }
            return rewritten;
        } catch (Throwable t) {
            // Keep the original bytes: the remaining shim layer still covers this class.
            LOGGER.error("failed to rewrite LWJGL2 call sites in {}", internal, t);
            return basicClass;
        }
    }

    private static String describeSource(String internal) {
        return String.valueOf(SOURCES.sourceNameOf(internal));
    }

    private static void report() {
        LOGGER.info("LWJGL2 filter ({}) after {} classes", MODE, CLASSIFIED.sum());
        LOGGER.info("  seen:      {}", SEEN);
        LOGGER.info("  rewritten: {}", REWRITTEN);
        if (SKIP) {
            LOGGER.info("  skipped:   {}", SKIPPED);
        }
        if (!isEmpty(DROPPED)) {
            LOGGER.info("  rewritten although a Cleanroom mod (a skip would drop these): {}", DROPPED);
            LOGGER.info("  sources:   {}", DROPPED_SOURCES);
        }
        if (!CLEANROOM_REWRITTEN.isEmpty()) {
            LOGGER.info("  Cleanroom mod classes rewritten: {}", CLEANROOM_REWRITTEN);
        }
    }

    private static boolean isEmpty(Map<ModSourceIndex.Kind, LongAdder> counters) {
        for (LongAdder counter : counters.values()) {
            if (counter.sum() != 0) {
                return false;
            }
        }
        return true;
    }

    private static Map<ModSourceIndex.Kind, LongAdder> counters() {
        Map<ModSourceIndex.Kind, LongAdder> map = new EnumMap<>(ModSourceIndex.Kind.class);
        for (ModSourceIndex.Kind kind : ModSourceIndex.Kind.values()) {
            map.put(kind, new LongAdder());
        }
        return map;
    }

    private static boolean isExcluded(String internal) {
        for (String prefix : EXCLUDED_PREFIXES) {
            if (internal.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
