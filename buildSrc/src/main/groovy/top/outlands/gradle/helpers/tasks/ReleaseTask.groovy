package top.outlands.gradle.helpers.tasks

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import top.outlands.gradle.helpers.Http
import top.outlands.gradle.helpers.ReleaseVersions

import java.time.Instant

/**
 * Starts a release from the command line: {@code ./gradlew release}.
 *
 * The task never builds or publishes anything itself. It checks that the repository is in a releasable state,
 * computes the version, and asks CI to do the work (the build and the upload happen in 'Release', which also creates
 * the tag - and only after the artifacts are on maven, so a failed release does not leave a tag behind).
 *
 * State is never mutated here: git is only asked questions, and nothing outside the GitHub API is touched. In
 * particular it does not 'git pull' - being behind the remote is reported, not fixed.
 *
 * Guards, in order: on the release branch, clean tree, exactly the remote's commit, a free version, a token.
 */
@DisableCachingByDefault(because = 'Talks to the GitHub API and shells out to git')
abstract class ReleaseTask extends DefaultTask {

    /** GitHub repository, {@code owner/name}. */
    @Input
    abstract Property<String> getRepository()

    @Input
    abstract Property<String> getDefaultBranch()

    @Input
    abstract Property<String> getWorkflowFile()

    @Input
    abstract Property<String> getMavenMetadataUrl()

    @Input
    abstract Property<String> getGitRemote()

    @Input
    @Optional
    abstract Property<String> getCalverDate()

    /** GitHub token with Actions: write; when unset it is looked up in env / ~/.gradle/gradle.properties. */
    @Input
    @Optional
    abstract Property<String> getToken()

    @Input
    abstract Property<Boolean> getDryRun()

    /** Do not wait for the release to finish (the default is to wait and verify). */
    @Input
    abstract Property<Boolean> getNoWait()

    /** Finish an already started release instead of starting a new one. */
    @Input
    @Optional
    abstract Property<String> getResumeVersion()

    @Input
    abstract Property<Integer> getTimeoutMinutes()

    @Internal
    abstract DirectoryProperty getRootDir()

    private final Instant startedAt = Instant.now()
    private File root
    private String head
    private String token

    @TaskAction
    void action() {
        root = getRootDir().get().asFile
        String remote = getGitRemote().get()
        String defaultBranch = getDefaultBranch().get()

        section('repository')
        gitResult('fetch', '--prune', '--tags', remote)
        String branch = git('symbolic-ref', '--short', 'HEAD').first().trim()
        if (branch != defaultBranch) {
            fail("""A release has to be made from '${defaultBranch}', but HEAD is on '${branch}'.
  git switch ${defaultBranch}""")
        }
        def dirty = git('status', '--porcelain')
        if (!dirty.isEmpty()) {
            fail("""The working tree has uncommitted changes; a release has to build exactly what is committed.
${dirty.take(10).collect { '  ' + it }.join('\n')}
${dirty.size() > 10 ? "  ... and ${dirty.size() - 10} more\n" : ''}  git commit  # or: git stash""")
        }
        head = git('rev-parse', 'HEAD').first().trim()
        String remoteHead = git('rev-parse', "${remote}/${defaultBranch}").first().trim()
        checkUpToDate(defaultBranch, remoteHead, remote)
        println("> ${defaultBranch} @ ${head.take(8)} ${git('log', '-1', '--format=%s').first()}")

        section('version')
        String resumeVersion = getResumeVersion().getOrElse('')
        String version
        String date
        if (resumeVersion) {
            if (!ReleaseVersions.isCalver(resumeVersion)) {
                fail("'${resumeVersion}' is not a CalVer version (YYYY.MM.DD or YYYY.MM.DD-N).")
            }
            date = ReleaseVersions.dateOf(resumeVersion)
            version = resumeVersion
            println("> resuming ${version}")
        } else {
            date = getCalverDate().getOrElse(ReleaseVersions.utcToday())
            def taken = ReleaseVersions.taken(date, getMavenMetadataUrl().get(), root, remote)
            if (!taken.isEmpty()) println("> already released on ${date}: ${taken.join(', ')}")
            version = ReleaseVersions.next(date, taken)
            println("> version ${version}")
        }
        int releaseNumber = ReleaseVersions.releaseNumberOf(version)
        if (releaseNumber < 1) fail("'${version}' is not a CalVer version (YYYY.MM.DD or YYYY.MM.DD-N).")
        if (releaseNumber == 2 && !resumeVersion) {
            println('> this is the second release of the day, so the version gets the -2 modifier')
        }

        if (getDryRun().getOrElse(false)) {
            println("""
Dry run: would release ${version} from ${defaultBranch} @ ${head.take(8)}.
Nothing was dispatched.""")
            return
        }

        section('dispatch')
        token = resolveToken()
        if (!token) {
            fail("""No GitHub token. Dispatching a release needs a fine-grained PAT with 'Actions: Read and write' on
${getRepository().get()}, kept outside the repository - for example:
  echo 'releaseToken=github_pat_...' >> ~/.gradle/gradle.properties
      (Windows: %USERPROFILE%\\.gradle\\gradle.properties)
The token is passed as -PreleaseToken=... or through OUTLANDS_RELEASE_TOKEN as well. See the README for details.""")
        }
        String dispatchUrl = "${apiBase()}/actions/workflows/${getWorkflowFile().get()}/dispatches".toString()
        String body = JsonOutput.toJson([
                ref   : defaultBranch,
                inputs: [version: version, commit: head, resume: resumeVersion ? 'true' : 'false'],
        ])
        def response = Http.post(dispatchUrl, Http.github(token), body)
        // GitHub documents 204 for a dispatch but answers 200 with the created run. Accept both, and use the run it
        // reports instead of searching for it afterwards - that also removes the guessing from the fallback path.
        if (response.status != 204 && response.status != 200) fail(dispatchFailure(response))
        println("> dispatched ${getWorkflowFile().get()} on ${defaultBranch}")

        def run = dispatchedRun(response) ?: awaitRunStarted(defaultBranch)
        println("> run ${run.html_url}")

        if (getNoWait().getOrElse(false)) {
            println("""
Not waiting (-PnoWait): ${version} is being built and published.
  ${run.html_url}""")
            return
        }

        def finished = awaitCompletion(run.id as long)
        boolean published = verify(version)
        if (finished.conclusion != 'success') {
            // A red run does not have to mean a failed release: the workflow may have broken after publishing
            // (its own verification step, the CHANGELOG commit, ...). What counts is whether the artifacts and the
            // release are actually there.
            if (!published) fail(failedRun(finished, version))
            println("> warning: the run ended as '${finished.conclusion}' after the release was published:")
            println(">   ${finished.html_url}")
        } else if (!published) {
            fail("""The run reported success, but ${version} is not where it should be - see ${finished.html_url}""")
        }
        println("""
released ${version} in ${elapsed()}
  https://github.com/${getRepository().get()}/releases/tag/${version}
  ${mavenBaseUrl()}/top/outlands/outlands/${version}/""")
    }

    // region checks ---------------------------------------------------------

    private void checkUpToDate(String defaultBranch, String remoteHead, String remote) {
        if (head == remoteHead) return
        if (isAncestor(head, remoteHead)) {
            fail("""${defaultBranch} is behind ${remote}/${defaultBranch} (${head.take(8)} vs ${remoteHead.take(8)}).
Pull first, then run this again - being out of date is reported here, never fixed behind your back:
  git pull --ff-only""")
        }
        if (isAncestor(remoteHead, head)) {
            fail("""${defaultBranch} has commits that are not pushed yet (${head.take(8)} vs ${remoteHead.take(8)}).
A release has to be reproducible from the remote:
  git push""")
        }
        fail("""${defaultBranch} and ${remote}/${defaultBranch} have diverged (${head.take(8)} vs ${remoteHead.take(8)}).
Reconcile them before releasing.""")
    }

    private boolean isAncestor(String maybeAncestor, String commit) {
        return gitResult('merge-base', '--is-ancestor', maybeAncestor, commit).exit == 0
    }

    // endregion

    // region GitHub API -----------------------------------------------------

    private Map dispatchedRun(Map response) {
        if (!response.body) return null
        try {
            def payload = new JsonSlurper().parseText(response.body as String)
            if (payload instanceof Map && payload.workflow_run_id != null) {
                return [id: payload.workflow_run_id, html_url: payload.html_url]
            }
        } catch (Exception ignored) {
            // an unexpected body only means falling back to searching for the run
        }
        return null
    }

    private Map awaitRunStarted(String defaultBranch) {
        // created_at is second-precision, so look slightly further back to survive clock skew
        Instant since = Instant.now().minusSeconds(30)
        long deadline = System.currentTimeMillis() + 3 * 60_000
        String url = "${apiBase()}/actions/runs?event=workflow_dispatch&branch=${defaultBranch}&per_page=30".toString()
        while (System.currentTimeMillis() < deadline) {
            def json = json(Http.get(url, Http.github(token)))
            def run = (json.workflow_runs ?: []).find { entry ->
                (entry.head_sha as String) == head &&
                        (entry.path as String).endsWith("/${getWorkflowFile().get()}") &&
                        Instant.parse(entry.created_at as String).isAfter(since)
            }
            if (run != null) return run
            sleep(5)
        }
        fail("""The workflow was dispatched but no run appeared within 3 minutes.
Check that '${getWorkflowFile().get()}' has a 'workflow_dispatch' trigger on ${defaultBranch}.""")
    }

    private Map awaitCompletion(long runId) {
        long deadline = System.currentTimeMillis() + getTimeoutMinutes().getOrElse(20) * 60_000L
        long lastReport = 0
        String url = "${apiBase()}/actions/runs/${runId}".toString()
        while (System.currentTimeMillis() < deadline) {
            def run = json(Http.get(url, Http.github(token)))
            if (run.status == 'completed') return run
            if (System.currentTimeMillis() - lastReport > 30_000) {
                println("> ${run.status}: ${elapsed()}")
                lastReport = System.currentTimeMillis()
            }
            sleep(15)
        }
        fail("""The release is still running after ${getTimeoutMinutes().getOrElse(20)} minutes; stop waiting with -PnoWait
next time. Follow it at:
  https://github.com/${getRepository().get()}/actions/runs/${runId}""")
    }

    /** True when the release and the published artifacts are actually there. */
    private boolean verify(String version) {
        List<String> problems = []
        if (Http.get("${apiBase()}/releases/tags/${version}".toString(), Http.github(token)).status != 200) {
            problems << "GitHub has no release for ${version}"
        }
        def metadata = Http.get(getMavenMetadataUrl().get())
        if (metadata.status != 200 || !(metadata.body as String).contains("<version>${version}</version>")) {
            problems << "maven does not list ${version} (${getMavenMetadataUrl().get()})"
        }
        problems.each { println("> missing: ${it}") }
        return problems.isEmpty()
    }

    private String dispatchFailure(Map response) {
        int status = response.status as int
        String hint
        if (status == 401) {
            hint = 'The token was rejected: it is expired or revoked. Create a new fine-grained PAT.'
        } else if (status == 403) {
            hint = 'The token is valid but may not dispatch workflows: give it Actions: Read and write on this repository.'
        } else if (status == 404) {
            hint = "Not found: either the token cannot see ${getRepository().get()}, or it may not run ${getWorkflowFile().get()}."
        } else if (status == 422) {
            hint = 'The dispatch was rejected as invalid; the inputs do not match what the workflow declares.'
        } else {
            hint = 'Unexpected response.'
        }
        return """Could not dispatch the release (HTTP ${status}).
${hint}
${response.body}"""
    }

    private String failedRun(Map run, String version) {
        def jobs = []
        try {
            def json = json(Http.get("${apiBase()}/actions/runs/${run.id}/jobs".toString(), Http.github(token)))
            jobs = (json.jobs ?: []).findAll { it.conclusion == 'failure' }.collect { it.name }
        } catch (Exception ignored) {
            // the failure details are a convenience, not the point of the message
        }
        return """The release failed (${run.conclusion}): ${run.html_url}
failed jobs: ${jobs.isEmpty() ? '(none reported)' : jobs.join(', ')}

Nothing is tagged unless the artifacts reached maven, so ${version} is normally still free:
  ./gradlew release
If maven already holds ${version} but the tag or the GitHub release is missing, finish it with
  ./gradlew release -PreleaseVersion=${version}"""
    }

    private Map json(Map response) {
        if (response.status != 200) {
            fail("GitHub answered HTTP ${response.status}:\n${response.body}")
        }
        return new JsonSlurper().parseText(response.body as String) as Map
    }

    private String apiBase() {
        return "https://api.github.com/repos/${getRepository().get()}"
    }

    private String mavenBaseUrl() {
        return getMavenMetadataUrl().get().replaceAll('/top/outlands/outlands/maven-metadata\\.xml$', '')
    }

    // endregion

    // region plumbing -------------------------------------------------------

    private String resolveToken() {
        String fromProperty = getToken().getOrElse('')
        if (fromProperty) return fromProperty
        String fromEnv = System.getenv('OUTLANDS_RELEASE_TOKEN') ?: ''
        if (fromEnv) return fromEnv
        def properties = new File(System.getProperty('user.home'), '.gradle/gradle.properties')
        if (!properties.isFile()) return ''
        for (String line : properties.readLines()) {
            String trimmed = line.trim()
            if (trimmed.startsWith('releaseToken=')) return trimmed.substring('releaseToken='.length()).trim()
        }
        return ''
    }

    private List<String> git(String... args) {
        return ReleaseVersions.git(root, args)
    }

    private Map gitResult(String... args) {
        return ReleaseVersions.gitResult(root, args)
    }

    private void section(String name) {
        println("> ${name}")
    }

    private String elapsed() {
        long seconds = java.time.Duration.between(startedAt, Instant.now()).seconds
        if (seconds < 60) return "${seconds}s"
        return "${Long.toString(seconds.intdiv(60))}m${seconds % 60}s"
    }

    private static void sleep(int seconds) {
        Thread.sleep(seconds * 1000L)
    }

    private void fail(String message) {
        throw new GradleException(message)
    }

    // endregion
}
