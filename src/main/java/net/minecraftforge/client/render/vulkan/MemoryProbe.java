package net.minecraftforge.client.render.vulkan;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;

/**
 * Says how close the heap is to the wall, before it gets there.
 *
 * <p><b>Why this exists.</b> An {@code OutOfMemoryError} thrown inside the frame loop is caught by
 * {@code Minecraft.runGameLoop} and answered with a memory screen - and nothing is logged, because the catch that
 * shows the screen does not report. A session that dies that way leaves a log with no error in it at all, which is
 * the least diagnosable failure this renderer can produce: the only evidence is that the client stopped working.</p>
 *
 * <p>The measurement is the heap <i>after the last collection</i> - what is actually retained, not what has been
 * allocated since - because "used" from {@link Runtime} counts garbage and would warn on every frame that happens to
 * run just before a collection. It is read from the old generation's collection usage, which is the number a leak
 * moves.</p>
 *
 * <p>Cost: no allocation and no native call when the heap is comfortable; one pass over a handful of pool beans
 * every two seconds when it is not.</p>
 */
final class MemoryProbe {

    private static final Logger LOGGER = LogManager.getLogger("Outlands|render");

    /** Warn once per this many nanoseconds, so a full heap does not print sixty lines a second. */
    private static final long WARN_INTERVAL_NANOS = 2_000_000_000L;
    private static volatile long nextWarnAt;

    private MemoryProbe() {
    }

    /**
     * Subscribes to the old generation's usage threshold, once.
     *
     * <p>The per-frame check can miss entirely: an allocation that fails can be made between two frames, by a chunk
     * worker or by the game's own code, and the frame loop's own catch answers it with a screen and no log line. A
     * threshold notification from the memory subsystem arrives whichever thread crosses it, which is the difference
     * between "the client stopped working" and "the heap reached 7.4 GB at 21:04:12".</p>
     */
    private static void installThreshold() {
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() != MemoryType.HEAP || !pool.isUsageThresholdSupported()) {
                continue;
            }
            if (!(pool instanceof javax.management.NotificationEmitter)) {
                continue;
            }
            long threshold = Runtime.getRuntime().maxMemory() * 17L / 20L;
            pool.setUsageThreshold(threshold);
            ((javax.management.NotificationEmitter) pool).addNotificationListener(
                    (notification, handback) -> {
                        if (!(notification.getUserData()
                                instanceof javax.management.openmbean.CompositeData data)) {
                            return;
                        }
                        java.lang.management.MemoryNotificationInfo info =
                                java.lang.management.MemoryNotificationInfo.from(data);
                        if (info.getCount() == 0L) {
                            return;
                        }
                        LOGGER.warn("probe: {} crossed its {} MB threshold, {} MB used after collection;"
                                        + " the next allocation failure will be answered with a memory screen and"
                                        + " no log line",
                                pool.getName(), threshold >> 20, info.getUsage().getUsed() >> 20);
                    },
                    null, null);
        }
    }

    /** Bytes retained after the last collection, over every heap pool that reports it. */
    static long retained() {
        long retained = 0L;
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() != MemoryType.HEAP) {
                continue;
            }
            java.lang.management.MemoryUsage usage = pool.getCollectionUsage();
            if (usage != null) {
                retained += usage.getUsed();
            }
        }
        return retained;
    }

    /** Used by the periodic report: retained against the maximum, in megabytes. */
    static String report() {
        return (retained() >> 20) + "/" + (Runtime.getRuntime().maxMemory() >> 20) + " MB heap";
    }

    private static boolean installed;

    /** Called once a frame. Warns from 60% of the maximum: early enough to show a climb, not just the wall. */
    static void frameCompleted() {
        if (!installed) {
            installed = true;
            installThreshold();
        }
        long now = System.nanoTime();
        if (now - nextWarnAt < 0L) {
            return;
        }
        long max = Runtime.getRuntime().maxMemory();
        long retained = retained();
        if (retained * 5L < max * 3L) {
            return;
        }
        nextWarnAt = now + WARN_INTERVAL_NANOS;
        LOGGER.warn("probe: {} MB of the {} MB heap is still retained after collection - an out-of-memory failure"
                + " here is caught by the frame loop and leaves no trace in this log", retained >> 20, max >> 20);
    }
}
