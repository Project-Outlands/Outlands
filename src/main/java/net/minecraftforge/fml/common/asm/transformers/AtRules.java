package net.minecraftforge.fml.common.asm.transformers;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.google.common.io.CharSource;
import com.google.common.base.Splitter;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;

/**
 * Reads an access transformer file into {@link AtRule}s, once.
 *
 * <p>{@link AccessTransformer} builds its own table from this, and the index exports the same list for a language
 * server; two readers of one grammar, not two grammars. A line keeps the loader's format: {@code access class member},
 * the member omitted for a class rule, {@code *} for all members, {@code +f}/{@code -f} to set or clear final.
 */
public final class AtRules {

    private AtRules() {}

    /**
     * @param source the rules file
     * @return the rules in file order
     * @throws RuntimeException on a line with more than three parts, like the transformer does
     */
    public static List<AtRule> parse(CharSource source) throws IOException {
        final List<AtRule> rules = new ArrayList<>();
        for (String line : source.readLines()) {
            final String text = Iterables.getFirst(Splitter.on('#').limit(2).split(line), "").trim();
            if (text.isEmpty()) {
                continue;
            }
            final List<String> parts = Lists.newArrayList(Splitter.on(' ').trimResults().split(text));
            if (parts.size() > 3) {
                throw new RuntimeException("Invalid config file line " + line);
            }
            final Access access = new Access(parts.get(0));
            final String owner = parts.get(1);
            if (parts.size() == 2) {
                rules.add(new AtRule(owner, "", "", access.visibility, true, access.changeFinal, access.markFinal));
                continue;
            }
            final String reference = parts.get(2);
            final int paren = reference.indexOf('(');
            final String name = paren > 0 ? reference.substring(0, paren) : reference;
            final String descriptor = paren > 0 ? reference.substring(paren) : "";
            rules.add(new AtRule(owner, name, descriptor, access.visibility, false, access.changeFinal, access.markFinal));
        }
        return rules;
    }

    /** The access word of a rule line, parsed the way the transformer does. */
    private record Access(int visibility, boolean changeFinal, boolean markFinal) {

        Access(String word) {
            this(visibilityOf(word), word.endsWith("-f") || word.endsWith("+f"), word.endsWith("+f"));
        }

        private static int visibilityOf(String word) {
            if (word.startsWith("public")) {
                return org.objectweb.asm.Opcodes.ACC_PUBLIC;
            }
            if (word.startsWith("private")) {
                return org.objectweb.asm.Opcodes.ACC_PRIVATE;
            }
            if (word.startsWith("protected")) {
                return org.objectweb.asm.Opcodes.ACC_PROTECTED;
            }
            return 0;
        }
    }
}
