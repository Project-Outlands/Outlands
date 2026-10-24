package top.outlands.gradle.helpers.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

/**
 * Renames a source tree from SRG (func_12345_a / field_12345_a / p_1234_1_) to MCP names.
 *
 * The loader keeps its patches in SRG names: MCP names may not be redistributed, and SRG is what Forge ships in its
 * own 1.12 userdev. That leaves this build with SRG-named Minecraft sources after patching, while the loader sources
 * are written in MCP names, so the patched tree has to be renamed before it can be compiled with them.
 *
 * The mapping file is a TSRG (here the inverted MCP->SRG dump the mavenizer emits): class lines are
 * {@code srgName mcpName}, and every indented line below a class is a member, optionally followed by further
 * indented parameter renames. Only the MCP names are used, so the mapping is applied as a token rewrite; SRG tokens
 * (func_/field_/p_) are unique across the whole jar, so this is unambiguous.
 */
abstract class RenameSourcesTask extends DefaultTask {

    @InputDirectory
    abstract DirectoryProperty getInput()

    @InputFile
    abstract RegularFileProperty getMappings()

    @OutputDirectory
    abstract DirectoryProperty getOutput()

    @TaskAction
    void rename() {
        def map = new HashMap<String, String>()
        File mappings = getMappings().get().asFile
        String currentClassObf = null
        String currentClassMapped = null

        mappings.withInputStream { raw ->
        new java.util.zip.GZIPInputStream(raw).withReader('UTF-8') { reader ->
        reader.eachLine { String line ->
            if (line.isEmpty() || line.startsWith('#')) return
            def tokens = line.trim().split(/\s+/)
            if (line.charAt(0) != '\t' && !Character.isWhitespace(line.charAt(0))) {
                // class line: obf(deobf) mapped
                // in a map2srg dump the first column is the mcp name and the second the srg name
                // class line in a map2srg dump: <mcp> <srg>
                if (tokens[0] == 'tsrg2') return
                if (tokens.length > 1) map.put(tokens[1], tokens[0])
            } else if (tokens.length == 2 && tokens[1] ==~ /(func|field)_\d+_[a-zA-Z]+/) {
                // field: <mcp> <srg>
                map.put(tokens[1], tokens[0])
            } else if (tokens.length >= 3 && tokens[2] ==~ /(func|field)_\d+_[a-zA-Z]+/) {
                // method: <mcp> <descriptor> <srg>
                map.put(tokens[2], tokens[0])
            }
        }
        }
        }

        File out = getOutput().get().asFile
        out.deleteDir()
        out.mkdirs()

        def pattern = buildPattern(map.keySet())
        File input = getInput().get().asFile
        input.eachFileRecurse { File file ->
            if (!file.isFile()) return
            def relative = input.toPath().relativize(file.toPath()).toString()
            File target = new File(out, relative)
            target.parentFile.mkdirs()
            if (!relative.endsWith('.java')) {
                target.bytes = file.bytes
                return
            }
            def text = file.getText('UTF-8')
            target.setText(pattern.matcher(text).replaceAll { m -> map.getOrDefault(m.group(), m.group()) }, 'UTF-8')
        }

        logger.lifecycle("renamePatchedSources: ${map.size()} renames applied to ${input}")
    }

    /**
     * SRG tokens are all of the shape {@code func_\d+_[a-z]+}, {@code field_\d+_[a-z]+} or {@code p_\d+_\d+_}; matching
     * exactly those shapes keeps the rewrite away from string literals that merely contain the digits.
     */
    private static java.util.regex.Pattern buildPattern(Collection<String> keys) {
        def quoted = keys.findAll { it ==~ /(func|field)_\d+_[a-zA-Z]+|p_\d+_\d+_/ }.collect { java.util.regex.Pattern.quote(it) }
        if (quoted.isEmpty()) return java.util.regex.Pattern.compile('(?!)')
        return java.util.regex.Pattern.compile('(?<![\\w$])(' + quoted.join('|') + ')(?![\\w$])')
    }
}
