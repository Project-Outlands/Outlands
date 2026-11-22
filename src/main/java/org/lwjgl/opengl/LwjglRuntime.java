package org.lwjgl.opengl;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Compares the LWJGL release that is on the classpath with the one the shims are compiled against.
 * <p>
 * The shims are compiled against one LWJGL release, but which release runs is decided by the launcher: our installer
 * ships {@code lwjgl_version}, while Android launchers pin a build of their own, because only theirs carries the
 * bionic natives (Zalith and Pojav ship 3.4.1). Those builds are close enough to run, but not identical -
 * {@link GLCapabilities} only gained the {@code GL_APPLE_*} flags in 3.4.2 - so a launch has to say which release it
 * got and what that costs, instead of dying with a bare {@link NoSuchFieldError} inside {@code Display.create}.
 * <p>
 * Both bounds are read from {@code lwjgl.properties}, which {@code processResources} fills from
 * {@code gradle.properties}, so each release is named exactly once - for the installer dependencies, the shims and the
 * capability test.
 */
final class LwjglRuntime {
    private static final Logger LOGGER = LogManager.getLogger("Outlands|lwjgl");

    /** Release the shims are compiled against; the capability tables are generated from its {@code GLCapabilities}. */
    private static final String COMPILED = property("compiled");
    /** Oldest release that can run this build: the first one carrying the SDL bindings {@link Display} is built on. */
    private static final String MINIMUM = property("minimum");

    /** Flags the runtime's {@link GLCapabilities} does not have, collected so they are reported as one line. */
    private static final Set<String> ABSENT_FLAGS = ConcurrentHashMap.newKeySet();

    private static volatile String runtime;
    private static boolean verified;
    private static boolean absentReported;

    private LwjglRuntime() {
    }

    /** Runs once, right before the first capability table is built; refuses to start on a release below the floor. */
    static void verify() {
        if (verified) return;
        synchronized (LwjglRuntime.class) {
            if (verified) return;
            verified = true;
            runtime = org.lwjgl.Version.getVersion();
            // No properties (tests, an IDE run without resources) or a version string this cannot read: nothing to
            // compare, and guessing would be worse than staying quiet.
            if (COMPILED == null || MINIMUM == null || parse(runtime) == null) return;
            if (compareVersions(runtime, MINIMUM) < 0) {
                String message = "LWJGL " + runtime + " is older than the oldest release this build supports ("
                        + MINIMUM + "; compiled against " + COMPILED + "). Update the launcher's LWJGL, or let the"
                        + " Outlands installer supply it.";
                LOGGER.error(message);
                throw new IllegalStateException(message);
            }
            if (compareVersions(runtime, COMPILED) < 0) {
                LOGGER.info("LWJGL {} is older than the {} this build is compiled against; capability flags it does"
                        + " not expose fall back to false", runtime, COMPILED);
            } else {
                LOGGER.debug("LWJGL {} (compiled against {})", runtime, COMPILED);
            }
        }
    }

    /** Remembers a flag the runtime could not answer, so a skewed launch is diagnosable from its log. */
    static void noteMissingCapability(String name) {
        ABSENT_FLAGS.add(name);
    }

    /** Reports what the runtime could not answer, once per JVM: one line instead of one per flag. */
    static void reportMissingCapabilities() {
        if (ABSENT_FLAGS.isEmpty() || absentReported) return;
        synchronized (LwjglRuntime.class) {
            if (absentReported) return;
            absentReported = true;
        }
        LOGGER.warn("LWJGL {} does not expose {} GLCapabilities flags this build is compiled against ({}); those"
                        + " LWJGL2 flags stay false. Android launchers pin a build of their own on purpose.",
                runtimeVersion(), ABSENT_FLAGS.size(), String.join(", ", new TreeSet<>(ABSENT_FLAGS)));
    }

    private static String runtimeVersion() {
        String current = runtime;
        return current == null ? org.lwjgl.Version.getVersion() : current;
    }

    /**
     * Compares two releases on their first three numeric components, ignoring a qualifier such as
     * {@code -snapshot}. Returns 0 when either side is not a version, so callers keep their conservative behaviour.
     */
    static int compareVersions(String left, String right) {
        int[] a = parse(left);
        int[] b = parse(right);
        if (a == null || b == null) return 0;
        for (int i = 0; i < 3; i++) {
            if (a[i] != b[i]) return Integer.compare(a[i], b[i]);
        }
        return 0;
    }

    /**
     * {@code 3.4.1-snapshot} and {@code 3.4.3+4} -> {@code [3, 4, 1]} and {@code [3, 4, 3]}: the first three numbers,
     * whether they are separated by dots, dashes or build metadata. Null when there is no {@code major.minor} to read.
     */
    private static int[] parse(String version) {
        if (version == null || version.isEmpty() || !Character.isDigit(version.charAt(0))) return null;
        int[] numbers = new int[3];
        int found = 0;
        for (String part : version.split("[^0-9]+")) {
            if (found == 3) break;
            if (part.isEmpty() || part.length() > 9) return null;
            numbers[found++] = Integer.parseInt(part);
        }
        return found >= 2 ? numbers : null;
    }

    private static String property(String key) {
        try (InputStream stream = LwjglRuntime.class.getResourceAsStream("/lwjgl.properties")) {
            if (stream == null) return null;
            Properties properties = new Properties();
            properties.load(stream);
            return properties.getProperty(key);
        } catch (IOException e) {
            LOGGER.debug("Could not read lwjgl.properties", e);
            return null;
        }
    }
}
