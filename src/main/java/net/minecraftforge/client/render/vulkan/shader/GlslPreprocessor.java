package net.minecraftforge.client.render.vulkan.shader;

import java.util.ArrayList;
import java.util.List;

/**
 * Brings OpenGL-era GLSL forward to something shaderc can compile for Vulkan.
 *
 * <p><b>Why this is necessary rather than convenient.</b> shaderc refuses any version below 1.40 when the target is
 * Vulkan SPIR-V, and every one of the game's post-processing shaders - the entity outline among them - is
 * {@code #version 120}. A mod or shader pack is no different: the sources in the wild are written for a GL context,
 * not for SPIR-V. So the text has to be rewritten before it reaches the compiler, and that rewriting is this
 * class's entire job.</p>
 *
 * <p><b>What is rewritten.</b> Only what 1.50 renamed or removed, and only in code - never inside a comment:</p>
 * <ul>
 *   <li>{@code attribute} becomes {@code in} in a vertex shader;</li>
 *   <li>{@code varying} becomes {@code out} in a vertex shader and {@code in} in a fragment shader;</li>
 *   <li>{@code gl_FragColor} becomes a declared output. GLSL 1.50 has no such built-in;</li>
 *   <li>{@code texture2D}, {@code texture2DProj}, {@code textureCube} and {@code shadow2D} become their
 *       {@code texture*} equivalents;</li>
 *   <li>plain uniforms are collected into one uniform block. Vulkan GLSL does not allow a non-opaque uniform outside
 *       a block at all, and every one of the game's post-processing shaders declares several
 *       ({@code uniform mat4 ModelViewMat;} and friends). Samplers stay where they are: those are opaque and legal
 *       on their own.</li>
 * </ul>
 *
 * <p>Attribute locations and uniform bindings are not handled here: shaderc assigns those when asked
 * ({@link GlslCompiler} asks). That split keeps this a text rewrite with no opinions about the pipeline, which is
 * the only way it can be tested against the game's own shaders.</p>
 */
public final class GlslPreprocessor {

    /**
     * The version the rewrite targets.
     *
     * <p>450 rather than the 1.40 minimum, because the two things this rewrite has to add - an explicit
     * {@code binding} on the uniform block and locations on user inputs and outputs - are only core syntax from 420
     * and 430 onwards. Targeting the minimum would mean emitting {@code #extension} lines to get them back, which is
     * strictly more to go wrong.</p>
     */
    private static final String TARGET_VERSION = "450";

    /** The name the collected uniforms are reached through after the rewrite. */
    private static final String UNIFORM_BLOCK = "outlandsUniforms";
    /**
     * Identifiers that are legal in GLSL 1.20 and reserved in the 4.x the rewrite targets.
     *
     * <p>{@code sample} is the one that bites: it became a built-in interpolation function in 4.00, and vanilla's own
     * blur shader declares {@code vec4 sample = ...}. Defined away to a name that cannot collide, which renames the
     * declaration and every use together. The trade-off is stated rather than hidden: a shader that <i>calls</i> the
     * 4.x built-in {@code sample()} would have the call renamed too, which is why only the words already reserved by
     * the sources this has to compile are listed.</p>
     */
    private static final List<String> RESERVED_SINCE_4X = List.of("sample");

    /** The block's type name, which has to differ from the instance name in a named block. */
    private static final String UNIFORM_BLOCK_TYPE = "OutlandsUniforms";

    private GlslPreprocessor() {}

    /**
     * Rewrites {@code source} so that it can be compiled for Vulkan.
     *
     * @return the rewritten source. A source that is already 1.40 or newer is returned with its built-ins rewritten
     *         and its version untouched, which is what {@code gl_FragColor} in a 1.50 shader needs.
     */
    public static String forVulkan(String source, GlslCompiler.Stage stage) {
        if (source == null) {
            throw new IllegalArgumentException("shader source is null");
        }
        List<String> lines = new ArrayList<>(List.of(source.split("\n", -1)));

        int versionIndex = -1;
        int version = 0;
        for (int i = 0; i < lines.size(); i++) {
            String trimmed = lines.get(i).trim();
            if (trimmed.startsWith("#version")) {
                versionIndex = i;
                version = parseVersion(trimmed);
                break;
            }
        }
        if (versionIndex < 0) {
            // GLSL 1.10 has no #version directive. Treating it as 110 is what the spec says, and it needs the same
            // rewrite as 120.
            version = 110;
        }

        boolean needsOutput = source.contains("gl_FragColor");
        List<String> rewritten = new ArrayList<>(lines.size() + 2);
        // One scanner across the whole source: a block comment that opens on one line and closes on another has to
        // stay a comment in between, and a per-line flag forgets that.
        boolean[] inBlockComment = {false};
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (i == versionIndex) {
                rewritten.add("#version " + (version < 140 ? TARGET_VERSION : String.valueOf(version)));
                continue;
            }
            rewritten.add(rewriteCode(line, stage, inBlockComment));
        }
        if (versionIndex < 0) {
            // No directive: it goes at the very top, where a #version must be.
            rewritten.add(0, "#version " + TARGET_VERSION);
            versionIndex = 0;
        }
        // Renamed before anything else is inserted, so the defines are in effect for the whole source.
        for (String reserved : RESERVED_SINCE_4X) {
            if (containsWord(source, reserved)) {
                rewritten.add(versionIndex + 1, "#define " + reserved + " outlands_" + reserved);
                versionIndex++;
            }
        }
        if (needsOutput && stage == GlslCompiler.Stage.FRAGMENT) {
            // Declared immediately after the version so it is in scope for every function, and named so it cannot
            // collide with a shader's own identifier.
            rewritten.add(versionIndex + 1, "out vec4 outlandsFragColor;");
        }
        return wrapPlainUniforms(rewritten, versionIndex);
    }

    /**
     * Moves plain uniforms into a block and redirects their uses through it.
     *
     * <p>This is the rewrite that makes the game's shaders compile at all: glslang refuses "non-opaque uniforms
     * outside a block" when the target is Vulkan, and {@code uniform mat4 ModelViewMat;} is exactly that. The block is
     * not given an explicit binding - the compiler assigns one along with everything else, which is what keeps this
     * from having to know the pipeline layout.</p>
     */
    private static String wrapPlainUniforms(List<String> lines, int versionIndex) {
        List<String> members = new ArrayList<>();
        List<String> names = new ArrayList<>();
        List<String> kept = new ArrayList<>(lines.size());

        for (String line : lines) {
            String member = plainUniformMember(line);
            if (member == null) {
                kept.add(line);
                continue;
            }
            members.add(member);
            names.add(identifierOf(member));
        }
        if (members.isEmpty()) {
            return String.join("\n", kept);
        }

        // Rewrite uses. Declarations are already gone, so every remaining occurrence is a read.
        for (int i = 0; i < kept.size(); i++) {
            String line = kept.get(i);
            if (line.trim().startsWith("#")) {
                continue;
            }
            boolean[] inBlockComment = {false};
            kept.set(i, rewriteIdentifiers(line, names, inBlockComment));
        }

        List<String> block = new ArrayList<>();
        block.add("layout(binding = 0) uniform " + UNIFORM_BLOCK_TYPE + " {");
        for (String member : members) {
            block.add("    " + member + ";");
        }
        block.add("} " + UNIFORM_BLOCK + ";");
        kept.addAll(versionIndex + 1, block);
        return String.join("\n", kept);
    }

    /** The declaration text of a plain uniform on this line, or null when the line is not one. */
    private static String plainUniformMember(String line) {
        String trimmed = line.trim();
        if (!trimmed.startsWith("uniform ")) {
            return null;
        }
        String rest = trimmed.substring("uniform ".length()).trim();
        if (rest.endsWith(";")) {
            rest = rest.substring(0, rest.length() - 1).trim();
        }
        // An initialiser is legal on a GL uniform and illegal on a block member, and the value comes from the
        // buffer anyway. Vanilla uses them: "uniform float MosaicSize = 1.0;" appears in overlay.fsh.
        int equals = rest.indexOf('=');
        if (equals >= 0) {
            rest = rest.substring(0, equals).trim();
        }
        String[] parts = rest.split("\\s+");
        if (parts.length < 2) {
            return null;
        }
        String type = parts[0];
        if (type.startsWith("sampler") || type.startsWith("image") || type.equals("atomic_uint")) {
            return null;
        }
        // Everything after the type is the declarator: name, and possibly an array size.
        String declarator = rest.substring(type.length()).trim();
        if (declarator.isEmpty() || !Character.isJavaIdentifierStart(declarator.charAt(0))) {
            return null;
        }
        return type + " " + declarator;
    }

    private static String identifierOf(String member) {
        String declarator = member.substring(member.indexOf(' ') + 1);
        int end = 0;
        while (end < declarator.length() && Character.isJavaIdentifierPart(declarator.charAt(end))) {
            end++;
        }
        return declarator.substring(0, end);
    }

    /** Replaces whole-word occurrences of {@code names} with {@code block.name}, outside comments. */
    private static String rewriteIdentifiers(String line, List<String> names, boolean[] inBlockComment) {
        StringBuilder out = new StringBuilder(line.length() + 16);
        int i = 0;
        while (i < line.length()) {
            if (inBlockComment[0]) {
                int end = line.indexOf("*/", i);
                if (end < 0) {
                    out.append(line, i, line.length());
                    return out.toString();
                }
                out.append(line, i, end + 2);
                i = end + 2;
                inBlockComment[0] = false;
                continue;
            }
            if (line.startsWith("/*", i)) {
                inBlockComment[0] = true;
                out.append("/*");
                i += 2;
                continue;
            }
            if (line.startsWith("//", i)) {
                out.append(line, i, line.length());
                return out.toString();
            }
            if (Character.isJavaIdentifierStart(line.charAt(i))) {
                int start = i;
                while (i < line.length() && Character.isJavaIdentifierPart(line.charAt(i))) {
                    i++;
                }
                String word = line.substring(start, i);
                out.append(names.contains(word) ? UNIFORM_BLOCK + "." + word : word);
            } else {
                out.append(line.charAt(i));
                i++;
            }
        }
        inBlockComment[0] = false;
        return out.toString();
    }

    /** Whether {@code word} appears as a whole identifier, outside comments. */
    private static boolean containsWord(String source, String word) {
        boolean[] inBlockComment = {false};
        for (String line : source.split("\n", -1)) {
            String rewritten = rewriteCode(line, GlslCompiler.Stage.FRAGMENT, inBlockComment);
            int from = 0;
            while (true) {
                int at = rewritten.indexOf(word, from);
                if (at < 0) {
                    break;
                }
                boolean startOk = at == 0 || !Character.isJavaIdentifierPart(rewritten.charAt(at - 1));
                int end = at + word.length();
                boolean endOk = end >= rewritten.length() || !Character.isJavaIdentifierPart(rewritten.charAt(end));
                if (startOk && endOk) {
                    return true;
                }
                from = end;
            }
        }
        return false;
    }

    private static int parseVersion(String directive) {
        String[] parts = directive.substring("#version".length()).trim().split("\\s+");
        if (parts.length == 0 || parts[0].isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(parts[0]);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Rewrites the code on one line, leaving comments alone.
     *
     * <p>A line is split at its first {@code //}, and the code half is rewritten. Block comments are tracked across
     * lines, because a word like {@code varying} in a licence header is not a declaration and rewriting it would
     * corrupt the source that gets reported in a compile error.</p>
     */
    private static String rewriteCode(String line, GlslCompiler.Stage stage, boolean[] inBlockCommentState) {
        StringBuilder out = new StringBuilder(line.length());
        boolean inBlockComment = inBlockCommentState[0];
        int i = 0;
        while (i < line.length()) {
            if (inBlockComment) {
                int end = line.indexOf("*/", i);
                if (end < 0) {
                    out.append(line, i, line.length());
                    inBlockCommentState[0] = true;
                    return out.toString();
                }
                out.append(line, i, end + 2);
                i = end + 2;
                inBlockComment = false;
                continue;
            }
            if (line.startsWith("/*", i)) {
                inBlockComment = true;
                out.append("/*");
                i += 2;
                continue;
            }
            if (line.startsWith("//", i)) {
                out.append(line, i, line.length());
                inBlockCommentState[0] = false;
                return out.toString();
            }
            // Consume one identifier or a single character, so replacements only ever land on whole words.
            int start = i;
            if (Character.isJavaIdentifierStart(line.charAt(i))) {
                while (i < line.length() && Character.isJavaIdentifierPart(line.charAt(i))) {
                    i++;
                }
                out.append(replaceWord(line.substring(start, i), stage));
            } else {
                out.append(line.charAt(i));
                i++;
            }
        }
        inBlockCommentState[0] = false;
        return out.toString();
    }

    private static String replaceWord(String word, GlslCompiler.Stage stage) {
        return switch (word) {
            case "attribute" -> stage == GlslCompiler.Stage.VERTEX ? "in" : word;
            case "varying" -> stage == GlslCompiler.Stage.VERTEX ? "out" : "in";
            case "gl_FragColor" -> "outlandsFragColor";
            case "texture2D", "textureCube" -> "texture";
            case "texture2DProj" -> "textureProj";
            case "shadow2D" -> "texture";
            default -> word;
        };
    }
}
