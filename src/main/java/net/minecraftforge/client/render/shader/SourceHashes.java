package net.minecraftforge.client.render.shader;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * The hash a precompiled module records about the source text it replaces.
 *
 * <p><b>Why this is its own class.</b> Two callers need it and they have nothing else in common: the build-time tool that
 * writes a {@code .sha1} file beside each module, and the runtime loader that decides whether a module still describes
 * the shader beside it. The build tool runs without the game's runtime classpath, so this has to be reachable without
 * pulling in anything that logs, and everything else in this package logs.</p>
 */
public final class SourceHashes {

    private SourceHashes() {}

    /** The SHA-1 of {@code sourceText}'s UTF-8 bytes, lowercase hex. */
    public static String sha1(String sourceText) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1")
                    .digest((sourceText == null ? "" : sourceText).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hex.append(Character.forDigit((value >> 4) & 0xF, 16)).append(Character.forDigit(value & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // Every Java platform is required to provide SHA-1.
            throw new IllegalStateException("SHA-1 is required by every Java platform", e);
        }
    }
}
