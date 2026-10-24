package top.outlands.gradle.helpers.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import top.outlands.gradle.helpers.ReleaseVersions

/**
 * Prints the version a release started right now would use, e.g. {@code RELEASE_VERSION=2026.10.23-2}.
 *
 * This is the only implementation of the CalVer release arithmetic: {@code :release} uses it to decide what to
 * dispatch, and CI runs it with {@code -Pexpect=<version>} to double check that the version the caller asked for is
 * still the next free one. That check covers both directions at once - a version that is already taken (someone else
 * released meanwhile) and a version that is ahead of what is available (a wrong date, e.g. from a skewed clock).
 */
@DisableCachingByDefault(because = 'Reads the maven index and the remote tags at execution time')
abstract class ReleaseVersionTask extends DefaultTask {

    @Input
    abstract Property<String> getMavenMetadataUrl()

    /** The date to compute for; defaults to today in UTC. */
    @Input
    @Optional
    abstract Property<String> getCalverDate()

    @Input
    abstract Property<String> getGitRemote()

    /** When set, the task fails unless this is exactly the version it computed. */
    @Input
    @Optional
    abstract Property<String> getExpect()

    @Internal
    abstract DirectoryProperty getRootDir()

    @TaskAction
    void action() {
        String date = getCalverDate().getOrElse(ReleaseVersions.utcToday())
        def taken = ReleaseVersions.taken(date, getMavenMetadataUrl().get(), getRootDir().get().asFile, getGitRemote().get())
        String next = ReleaseVersions.next(date, taken)
        String expect = getExpect().getOrElse('')

        if (expect) {
            if (expect != next) {
                String expectDate = ReleaseVersions.dateOf(expect)
                String reason
                if (expectDate == null) {
                    reason = "'${expect}' is not a CalVer version"
                } else if (expect != expectDate && ReleaseVersions.releaseNumberOf(expect) < 2) {
                    reason = 'the first release of a day has no suffix; the same-day releases are -2, -3, ...'
                } else if (expectDate != date) {
                    reason = "it is dated ${expectDate}, but a release started now would be dated ${date}"
                } else {
                    reason = "${expectDate} is already taken by ${taken.isEmpty() ? '(nothing)' : taken.join(', ')}"
                }
                throw new GradleException("""cannot release ${expect}: ${reason}
the next free version is ${next}
This happens when the version was computed a while ago (another release landed meanwhile), when it does not match
today's date, or when it was edited by hand. Recompute it with: ./gradlew releaseVersion""")
            }
            logger.lifecycle("RELEASE_VERSION=${next} (checked against -Pexpect)")
            return
        }

        if (!taken.isEmpty()) logger.lifecycle("already taken on ${date}: ${taken.join(', ')}")
        println "RELEASE_VERSION=${next}"
    }
}
