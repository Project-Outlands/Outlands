package net.minecraftforge.fml.common.asm.transformers;

/**
 * One parsed access transformer rule, as the archive that declares it wrote it.
 *
 * <p>The AT files themselves are text in the jar; they are parsed here because the grammar (wildcards, {@code +f} and
 * {@code -f}, field against method) is the loader's, and a language server reimplementing it would be a second
 * implementation that could disagree. The rules stay attached to the archive that declares them, so the file holding
 * them remains a function of that jar alone; a reader unions every archive's rules before applying them, which is what
 * the transformer does with its own table.
 *
 * <p>This is data, not the application: {@code AccessTransformer} keeps the one implementation of what a rule does to
 * an access word, and a reader that wants the same answer applies the rule the way that class does.
 *
 * @param owner           the target class, in internal form
 * @param name            the member, or {@code *} for all of them
 * @param descriptor      the method descriptor; empty for a field rule (a wildcard method rule keeps its own text)
 * @param targetAccess    the visibility the rule asks for, one of the {@code ACC_PUBLIC/PRIVATE/PROTECTED} bits or 0
 * @param classVisibility whether the rule targets the class rather than a member
 * @param changeFinal     whether the rule also sets the final bit, and {@code markFinal} whether it sets or clears it
 */
public record AtRule(
        String owner,
        String name,
        String descriptor,
        int targetAccess,
        boolean classVisibility,
        boolean changeFinal,
        boolean markFinal) {
}
