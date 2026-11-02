package net.minecraftforge.fml.common;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.annotation.Nullable;

import net.minecraftforge.fml.common.discovery.JarDeclaration;
import net.minecraftforge.fml.common.discovery.MetadataJson;
import net.minecraftforge.fml.common.versioning.ArtifactVersion;
import net.minecraftforge.fml.common.versioning.DependencyParser;
import net.minecraftforge.fml.common.versioning.VersionParser;
import net.minecraftforge.fml.relauncher.Side;

import com.google.gson.JsonElement;

/**
 * One dependency a mod declares in its {@code mcmod.info} entry.
 *
 * <p>The annotation spells this as a string - {@code "required-after:forge@[14.23.5.2860,)"} - which crosses three
 * independent facts into one token: whether the dependency has to be satisfied, whether it sorts before or after, and
 * which side it exists on. The manifest-era metadata flattened the first two into three lists and lost the third; adding
 * a third would have meant nine lists. Here each fact is a field, so the cross product stays out of the format:
 *
 * <pre>
 * { "on": "forge", "version": "[14.23.5.2860,)", "required": true }   requires forge, any order
 * { "on": "baubles", "order": "after" }                                order only, if baubles is installed
 * { "on": "thaumcraft", "order": "before", "required": true }          requires it, and sorts before it
 * { "on": "jei", "order": "after", "side": "client" }                  only on the client
 * </pre>
 *
 * <p>Nothing is implied: an entry with only a version is refused rather than read as a requirement, the same way the
 * annotation refuses {@code amod} without an instruction. "It has a version, so it must be required" would be a guess,
 * and the guess would be invisible in a log.
 *
 * <p>The rules are the annotation's, not a new set: at most one order, at least one of {@code required} or
 * {@code order} (otherwise the entry would do nothing), at most one side, and a side-scoped dependency that is not this
 * side's is dropped entirely - the same reading {@link DependencyParser} has always given, including the quirk that a
 * client-only requirement does not exist on a server at all rather than becoming optional.
 */
public final class ModDependency {

    /** Sides a dependency can be restricted to. The absence of a value means "both". */
    public enum Order {
        BEFORE, AFTER;

        @Nullable
        static Order parse(@Nullable String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            return switch (value.trim().toUpperCase(Locale.ENGLISH)) {
                case "BEFORE" -> BEFORE;
                case "AFTER" -> AFTER;
                default -> null;
            };
        }
    }

    private static final Set<String> KNOWN_KEYS = Set.of("on", "version", "order", "required", "side");

    private final String target;
    @Nullable
    private final String versionRange;
    @Nullable
    private final Order order;
    private final boolean required;
    @Nullable
    private final JarDeclaration.Side side;
    /** Built once so the same instance can be filed under more than one list, see {@link #applyTo}. */
    private final ArtifactVersion reference;

    private ModDependency(String target, @Nullable String versionRange, @Nullable Order order, boolean required,
                          @Nullable JarDeclaration.Side side, ArtifactVersion reference) {
        this.target = target;
        this.versionRange = versionRange;
        this.order = order;
        this.required = required;
        this.side = side;
        this.reference = reference;
    }

    /**
     * Reads the {@code depends} array of one mod entry.
     *
     * @param element the array, or {@code null} when the entry declares none
     * @param where the sentence subject for messages, e.g. {@code "The mod entry for foo in the mcmod.info of bar.jar"}
     * @throws LoaderException on anything that would silently change what the mod asked for: a misspelt order, a
     *                         requirement with no effect, a mod id that can never match
     */
    public static List<ModDependency> parse(@Nullable JsonElement element, String where) {
        if (element == null || element.isJsonNull()) {
            return List.of();
        }
        if (!element.isJsonArray()) {
            throw new LoaderException(where + " declares \"depends\" as " + MetadataJson.describeType(element)
                    + ". Expected an array of dependencies, as in [{\"on\": \"forge\"}].");
        }
        List<ModDependency> dependencies = new ArrayList<>();
        Set<String> targets = new java.util.LinkedHashSet<>();
        for (JsonElement entry : element.getAsJsonArray()) {
            ModDependency dependency = parseOne(entry, where);
            if (!targets.add(dependency.target)) {
                // Two entries for one mod can only disagree or repeat: "after" here and "before" there is a cycle of
                // one, and two requirements are one requirement listed twice in every message that reports it.
                throw new LoaderException(where + " declares the dependency on \"" + dependency.target
                        + "\" more than once. One entry per mod, with as many of its facts as apply.");
            }
            dependencies.add(dependency);
        }
        return dependencies.isEmpty() ? List.of() : List.copyOf(dependencies);
    }

    private static ModDependency parseOne(@Nullable JsonElement entry, String where) {
        if (entry == null || !entry.isJsonObject()) {
            throw new LoaderException(where + " declares a dependency that is not an object. Each entry names the mod it"
                    + " depends on, as in {\"on\": \"forge\", \"version\": \"[14.23.5.2860,)\"}.");
        }
        com.google.gson.JsonObject object = entry.getAsJsonObject();
        for (String key : object.keySet()) {
            if (!KNOWN_KEYS.contains(key)) {
                // Reported rather than rejected, the same call the jar section makes for its own unknown keys: a newer
                // schema may add a field, and an older loader should say so rather than refuse to start.
                FMLLog.log.warn("{} declares the dependency key \"{}\", which this loader does not understand", where, key);
            }
        }

        String target = MetadataJson.optionalText(object.get("on"), where, "on");
        if (target == null || target.isBlank()) {
            throw new LoaderException(where + " declares a dependency without \"on\". The value is the mod id it"
                    + " depends on.");
        }
        target = target.trim();
        checkTarget(target, where);

        String versionRange = MetadataJson.optionalScalarText(object.get("version"), where, "version");
        if (versionRange != null) {
            versionRange = versionRange.trim();
            if (versionRange.isEmpty()) {
                versionRange = null;
            }
        }

        String orderText = MetadataJson.optionalText(object.get("order"), where, "order");
        Order order = Order.parse(orderText);
        if (orderText != null && !orderText.isBlank() && order == null) {
            throw new LoaderException(where + " declares the dependency order \"" + orderText.trim() + "\" for \""
                    + target + "\". Expected one of: before, after.");
        }

        boolean required = parseRequired(object.get("required"), where, target);
        JarDeclaration.Side side = parseSide(object.get("side"), where, target);

        if (!required && order == null) {
            // Same rule the annotation has: an entry that neither requires nor orders anything would be a dependency
            // that reads as declared and never has an effect.
            throw new LoaderException(where + " declares the dependency on \"" + target + "\" with neither \"required\""
                    + " nor an \"order\", so it would have no effect. Add \"required\": true or \"order\": \"after\".");
        }

        ArtifactVersion reference;
        try {
            reference = VersionParser.parseVersionReference(versionRange == null ? target : target + "@" + versionRange);
        } catch (RuntimeException e) {
            throw new LoaderException(where + " declares the version \"" + versionRange + "\" for the dependency on \""
                    + target + "\", which is not a version or a version range.", e);
        }

        return new ModDependency(target, versionRange, order, required, side, reference);
    }

    /**
     * Refuses ids that can never match a loaded mod, instead of accepting them and letting the dependency silently do
     * nothing. The annotation path only logs these; here the whole declaration is rejected, because the loader is the
     * only thing that can tell the author and this is the moment where it still costs nothing.
     */
    private static void checkTarget(String target, String where) {
        if (target.length() > 64) {
            throw new LoaderException(where + " declares a dependency on a mod id longer than 64 characters: \""
                    + target + "\".");
        }
        if (target.indexOf('@') >= 0 || target.indexOf(';') >= 0 || target.indexOf(':') >= 0) {
            throw new LoaderException(where + " declares a dependency on \"" + target + "\", which contains a character"
                    + " that belongs to the annotation syntax (@, ; or :). Put the version range in \"version\".");
        }
        if (!target.equals(target.toLowerCase(Locale.ENGLISH))) {
            throw new LoaderException(where + " declares a dependency on \"" + target + "\", but mod ids are lowercase,"
                    + " so this can never match a loaded mod. Write it as \"" + target.toLowerCase(Locale.ENGLISH) + "\".");
        }
    }

    private static boolean parseRequired(@Nullable JsonElement element, String where, String target) {
        if (element == null || element.isJsonNull()) {
            return false;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
            throw new LoaderException(where + " declares \"required\" as " + MetadataJson.describeType(element)
                    + " for the dependency on \"" + target + "\". Expected true or false.");
        }
        return element.getAsBoolean();
    }

    @Nullable
    private static JarDeclaration.Side parseSide(@Nullable JsonElement element, String where, String target) {
        String text = MetadataJson.optionalText(element, where, "side");
        if (text == null || text.isBlank()) {
            return null;
        }
        JarDeclaration.Side side = JarDeclaration.Side.parse(text);
        if (side == null) {
            throw new LoaderException(where + " declares the side \"" + text.trim() + "\" for the dependency on \""
                    + target + "\". Expected one of: client, server, both.");
        }
        // "both" is the absence of a restriction, so it reads as no side at all rather than as a third value to compare.
        return side == JarDeclaration.Side.BOTH ? null : side;
    }

    /**
     * Files this dependency under the lists the sorter reads.
     *
     * <p>The reference is the same instance in both the requirement set and the ordering list when the entry says
     * {@code required} and orders as well. That matters: {@code Loader} asks whether a dependency is required by looking
     * it up in the requirement set, and two separately parsed versions of the same reference do not compare equal.
     *
     * <p>A dependency scoped to the other side is dropped here, not reported: that is what the annotation has always
     * meant by {@code client-after:}, and a server has nothing to warn about.
     */
    void applyTo(DependencyParser.DependencyInfo info, @Nullable Side launchSide) {
        // A null launch side only happens outside a launch (a test, a tool reading metadata); nothing to filter against
        // then, and dropping a declaration nobody asked about would be worse than keeping it.
        if (side != null && launchSide != null && side.excludes(launchSide.name())) {
            return;
        }
        if (required) {
            info.requirements.add(reference);
        }
        if (order == Order.BEFORE) {
            info.dependants.add(reference);
        } else if (order == Order.AFTER) {
            info.dependencies.add(reference);
        }
    }

    /** @return the mod id this depends on */
    public String target() {
        return target;
    }

    /** @return the version range as declared, or {@code null} when any version satisfies it */
    @Nullable
    public String versionRange() {
        return versionRange;
    }

    /** @return whether a missing target stops the game, as opposed to only affecting load order */
    public boolean required() {
        return required;
    }

    @Nullable
    public Order order() {
        return order;
    }

    /** @return the side this dependency exists on, or {@code null} for both */
    @Nullable
    public JarDeclaration.Side side() {
        return side;
    }

    /** @return the same dependency in the annotation's spelling, for log lines a reader already knows */
    @Override
    public String toString() {
        StringBuilder text = new StringBuilder();
        if (side != null) {
            text.append(side.name().toLowerCase(Locale.ENGLISH)).append('-');
        }
        if (required) {
            text.append("required");
        }
        if (order != null) {
            if (!text.isEmpty() && text.charAt(text.length() - 1) != '-') {
                text.append('-');
            }
            text.append(order == Order.BEFORE ? "before" : "after");
        }
        return text.append(':').append(target).append(versionRange == null ? "" : "@" + versionRange).toString();
    }
}
