package top.outlands.gradle.helpers

import groovy.xml.XmlSlurper

import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.regex.Pattern

/**
 * Version arithmetic for releases.
 *
 * Versions are CalVer ({@code YYYY.MM.DD}, UTC) and a release number is only added when the day already has one:
 * the first release of the day is plain, the next ones are {@code -2}, {@code -3}, ... CalVer's RELEASE modifier
 * starts at 2 for the second build, and Maven's ComparableVersion ranks {@code 2026.10.23-2} above
 * {@code 2026.10.23}, so 'latest'/'release' keep resolving forwards.
 *
 * A version counts as taken when it is either published to maven or tagged, so a half-finished release can not be
 * handed out again by accident. Both sources are queried live: {@code git ls-remote} for the tags (never the local
 * clone, which may be stale) and the maven index for the published artifacts.
 */
class ReleaseVersions {

    static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern('yyyy.MM.dd')
    static final Pattern CALVER = Pattern.compile('^(\\d{4}\\.\\d{2}\\.\\d{2})(?:-(\\d+))?$')

    static String utcToday() {
        return LocalDate.now(ZoneOffset.UTC).format(FORMAT)
    }

    static boolean isCalver(String version) {
        return version != null && CALVER.matcher(version).matches()
    }

    /** The date part of a CalVer version, or null when it is not one. */
    static String dateOf(String version) {
        def matcher = CALVER.matcher(version ?: '')
        return matcher.matches() ? matcher.group(1) : null
    }

    /** The release number of a CalVer version: 1 for a plain date, N for {@code -N}. */
    static int releaseNumberOf(String version) {
        def matcher = CALVER.matcher(version ?: '')
        if (!matcher.matches()) return 0
        return matcher.group(2) == null ? 1 : matcher.group(2) as int
    }

    /** Every version of {@code date} that is already taken, sorted. */
    static TreeSet<String> taken(String date, String mavenMetadataUrl, File rootDir, String remote) {
        def versions = new TreeSet<String>()
        versions.addAll(published(date, mavenMetadataUrl))
        versions.addAll(tagged(date, rootDir, remote))
        return versions
    }

    /** Versions of {@code date} that are published to maven. A missing index (404) simply means "nothing yet". */
    static List<String> published(String date, String mavenMetadataUrl) {
        def response = Http.get(mavenMetadataUrl)
        if (response.status == 404) return []
        if (response.status != 200) {
            throw new IllegalStateException("cannot read the maven index (HTTP ${response.status}): ${mavenMetadataUrl}")
        }
        def versions = []
        try {
            def xml = new XmlSlurper().parseText(response.body as String)
            xml.versioning.versions.version.each { versions << it.text() }
        } catch (Exception e) {
            throw new IllegalStateException("cannot parse the maven index at ${mavenMetadataUrl}: ${e.message}", e)
        }
        return versions.findAll { dateOf(it) == date }
    }

    /** Versions of {@code date} that are tagged on the remote. */
    static List<String> tagged(String date, File rootDir, String remote) {
        return git(rootDir, 'ls-remote', '--tags', '--refs', remote)
                .collect { String line -> line.substring(line.lastIndexOf('/') + 1) }
                .findAll { dateOf(it) == date }
    }

    /** The version the next release should use: {@code date}, or {@code date-N} when the day already has releases. */
    static String next(String date, Collection<String> taken) {
        int highest = 0
        taken.each { highest = Math.max(highest, releaseNumberOf(it)) }
        return highest == 0 ? date : "${date}-${highest + 1}".toString()
    }

    /**
     * Runs git in {@code rootDir} and returns its output lines. A non-zero exit is turned into an exception: every
     * caller here needs the command to have worked, and a silent failure would mean guessing a version.
     */
    static List<String> git(File rootDir, String... args) {
        def result = gitResult(rootDir, args)
        if (result.exit != 0) {
            throw new IllegalStateException("git ${args.join(' ')} failed (exit ${result.exit}):\n${result.output.trim()}")
        }
        return result.output.readLines()
    }

    /** Like {@link #git}, but returns {@code [exit:int, output:String]} instead of throwing. */
    static Map<String, Object> gitResult(File rootDir, String... args) {
        def command = ['git'] + (args as List)
        def process = new ProcessBuilder(command).directory(rootDir).redirectErrorStream(true).start()
        def output = process.inputStream.text
        int exit = process.waitFor()
        return [exit: exit, output: output]
    }
}
