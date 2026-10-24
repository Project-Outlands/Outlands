package top.outlands.gradle.helpers.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

/**
 * Turns {@code templates/CleanroomVersion.java} into the {@code CleanroomVersion} class that the loader reports at
 * runtime.
 *
 * The version comes from {@code rootProject.version}, which the root build derives from the date (CalVer):
 *
 * <pre>
 *   %VERSION%        -> the release part     2026.10.23
 *   %BUILD_VERSION%  -> the full version     2026.10.23-2
 * </pre>
 *
 * The two only differ for the 2nd, 3rd, ... release of a day. A plain build of that day carries no suffix, so both
 * placeholders are equal there as well.
 */
abstract class GenVersionTask extends DefaultTask {

    /** Full project version, e.g. {@code 2026.10.23} or {@code 2026.10.23-2}. */
    @Input
    String version = project.rootProject.version.toString()

    @InputFile
    File getTemplate() {
        return project.rootProject.file('templates/CleanroomVersion.java')
    }

    /**
     * Generated into the build directory on purpose: the class used to be written back into {@code src/main/java} and
     * gitignored, so a stale copy silently shipped an old version string whenever nobody re-ran the task.
     */
    @OutputFile
    File getVersionClass() {
        return new File(project.layout.buildDirectory.get().asFile, 'generated/sources/cleanroomVersion/com/cleanroommc/common/CleanroomVersion.java')
    }

    @TaskAction
    void action() {
        // '2026.10.23-2' -> '2026.10.23'; anything that does not look like a CalVer version is reported as-is
        def calver = version =~ /^(\d{4}\.\d{2}\.\d{2})(-\d+)?$/
        String tag = calver ? calver.group(1) : version
        File output = getVersionClass()
        output.parentFile.mkdirs()
        output.withWriter { writer ->
            getTemplate().eachLine { line ->
                writer.write(line.replace('%VERSION%', tag).replace('%BUILD_VERSION%', version) + '\n')
            }
        }
    }
}
