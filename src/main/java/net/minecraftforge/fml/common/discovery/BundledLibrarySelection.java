package net.minecraftforge.fml.common.discovery;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.maven.artifact.versioning.ComparableVersion;

/**
 * Decides which of several bundled copies of one library goes on the classpath.
 *
 * <p>1.12.2 has a single class loader shared by every mod, so two versions of one library cannot both be present: only
 * one can be visible to a given class lookup, and which one that is used to be decided by the order jars happened to be
 * extracted in. That is the least debuggable arrangement available - the symptom is a {@code NoSuchMethodError} in a
 * mod that is nobody's fault.
 *
 * <p>So the copies are compared instead of raced. The highest version wins, and every copy that loses is named in the
 * log along with the copy that beat it, which turns "some mod is broken" into "jar A bundles 31.1, jar B bundles 32.0,
 * 32.0 is on the classpath".
 *
 * <p>Only version ordering is used. Maven-style ranges are deliberately not implemented: mods that bundle a library
 * pick a version and ship it, they do not negotiate a range, so a range engine would add a solver and a vocabulary
 * without changing a single outcome here. Two jars that do not declare a version both compare as {@code "0"}, and then
 * the earlier claim wins - which is exactly the pre-existing behaviour, only now it is stated instead of incidental.
 */
public final class BundledLibrarySelection {

    /**
     * One library a jar says it bundles.
     *
     * @param id the library's identity, either a coordinate or (when the declaration names none) the file name
     * @param version the declared version, {@code "0"} when the declaration names none
     * @param path the entry inside {@code source} the library was bundled as
     * @param source the bundling jar's file name, for the log line
     */
    public record Claim(String id, String version, String path, String source) {}

    private static final BundledLibrarySelection EMPTY = new BundledLibrarySelection(Map.of(), List.of());

    private final Map<String, Claim> winners;
    private final List<String> notes;

    private BundledLibrarySelection(Map<String, Claim> winners, List<String> notes) {
        this.winners = winners;
        this.notes = notes;
    }

    /** Selection with nothing contested, i.e. every claim would win. */
    public static BundledLibrarySelection empty() {
        return EMPTY;
    }

    /**
     * Picks one winner per library identity.
     *
     * <p>Claims are considered in the order they are offered, so a tie keeps the earlier one and the outcome does not
     * depend on sorting anything. That matters because the previous behaviour was "first extracted wins": keeping it
     * for equal versions is what makes this change safe for jars that do not declare versions at all.
     */
    public static BundledLibrarySelection select(List<Claim> claims) {
        if (claims.isEmpty()) {
            return EMPTY;
        }

        Map<String, List<Claim>> byId = new LinkedHashMap<>();
        for (Claim claim : claims) {
            byId.computeIfAbsent(claim.id(), key -> new ArrayList<>()).add(claim);
        }

        Map<String, Claim> winners = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();
        for (Map.Entry<String, List<Claim>> entry : byId.entrySet()) {
            List<Claim> group = entry.getValue();
            Claim winner = group.get(0);
            for (Claim candidate : group.subList(1, group.size())) {
                if (compareVersions(candidate.version(), winner.version()) > 0) {
                    winner = candidate;
                }
            }
            winners.put(entry.getKey(), winner);
            if (group.size() > 1) {
                notes.add(describe(entry.getKey(), winner, group));
            }
        }
        return new BundledLibrarySelection(Map.copyOf(winners), List.copyOf(notes));
    }

    /**
     * @return whether {@code claim} was beaten by another jar's copy of the same library, i.e. whether putting it on
     *         the classpath would contradict the decision
     */
    public boolean isShadowed(Claim claim) {
        Claim winner = winners.get(claim.id());
        return winner != null && !winner.equals(claim);
    }

    /** One human-readable line per contested library, for the loader to log once. */
    public List<String> notes() {
        return notes;
    }

    /** @return the winning version of {@code id}, or {@code null} when nothing claimed it */
    public String winnerVersion(String id) {
        Claim winner = winners.get(id);
        return winner == null ? null : winner.version();
    }

    private static String describe(String id, Claim winner, List<Claim> group) {
        StringBuilder text = new StringBuilder();
        text.append("Several jars bundle the library \"").append(id).append("\": using ")
                .append(winner.version()).append(" from ").append(winner.source());
        List<String> dropped = new ArrayList<>();
        for (Claim claim : group) {
            if (claim.equals(winner)) {
                continue;
            }
            dropped.add(claim.version() + " from " + claim.source());
        }
        if (!dropped.isEmpty()) {
            text.append("; not using ").append(String.join(", ", dropped));
        }
        return text.append(". Only one version can be on the classpath.").toString();
    }

    /**
     * Compares two declared versions the way Maven does, which is what the versions in the wild are written for
     * ({@code 1.0.1} &gt; {@code 1.0}, {@code 2.0-beta} &lt; {@code 2.0}). A declaration that a jar makes up is still
     * ordered deterministically, which is all this needs to be.
     */
    static int compareVersions(String left, String right) {
        return new ComparableVersion(left).compareTo(new ComparableVersion(right));
    }
}
