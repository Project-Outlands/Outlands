package top.outlands.gradle.helpers.tasks

import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

import java.security.MessageDigest
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipFile

/**
 * Downloads the vanilla asset index and every object it references into a directory Minecraft can be pointed at with
 * {@code --assetsDir}.
 *
 * The client jar only carries part of the vanilla resources (textures, models, blockstates, recipes, en_us.lang).
 * Sounds, the other 105 languages and the realms icons live in the asset index's objects, which a launcher normally
 * fetches. A development run has no launcher, and 1.12.2's {@code ResourceIndex} does not fall back: without
 * {@code indexes/<id>.json} it logs a single error and the game runs without sound and without translations
 * ("Missing sound for event: ..." once per sound event).
 *
 * The target directory is a cache, not an artifact of the build: it lives in the Gradle user home, objects that are
 * already present with the right hash are skipped, and nothing is downloaded unless a run task asks for it.
 */
abstract class DownloadAssetsTask extends DefaultTask {

    /** The mcp metadata zip; its {@code minecraft/version.json} carries the asset index id, url, sha1 and size. */
    @InputFile
    abstract RegularFileProperty getMetadata()

    /** Where {@code indexes/<id>.json} and {@code objects/<ab>/<sha1>} are written. */
    @OutputDirectory
    abstract DirectoryProperty getOutput()

    /** Object host; {@code https://resources.download.minecraft.net/} unless a mirror is configured. */
    @Input
    abstract Property<String> getRepository()

    /** Parallel downloads; the 1.12 index has 1305 objects, a handful of connections is enough. */
    @Input
    abstract Property<Integer> getConnections()

    @TaskAction
    void download() {
        def root = getOutput().get().asFile
        def index = version().assetIndex
        if (index == null)
            throw new GradleException("${getMetadata().get().asFile} has no 'assetIndex' in minecraft/version.json")

        def indexFile = new File(root, "indexes/${index.id}.json")
        fetch(index.url as String, indexFile, index.sha1 as String, (index.size ?: 0) as long)
        logger.lifecycle("Asset index ${index.id}: ${indexFile}")

        def objects = new JsonSlurper().parse(indexFile).objects ?: [:]
        def queue = new ConcurrentLinkedQueue(objects.entrySet().collect { entry ->
            [
                    path: entry.key as String,
                    hash: entry.value.hash as String,
                    size: (entry.value.size ?: 0) as long
            ]
        })
        def total = queue.size()
        def done = new AtomicInteger()
        def skipped = new AtomicInteger()
        def failed = new ConcurrentLinkedQueue<String>()
        def repository = getRepository().get().with { it.endsWith('/') ? it : it + '/' }
        def pool = Executors.newFixedThreadPool(Math.max(1, getConnections().get()))
        def task = this
        try {
            def worker = {
                while (true) {
                    def item = queue.poll()
                    if (item == null) return
                    def file = new File(root, "objects/${item.hash.substring(0, 2)}/${item.hash}")
                    try {
                        if (file.isFile() && file.length() == item.size) {
                            skipped.incrementAndGet()
                        } else {
                            task.fetch("${repository}${item.hash.substring(0, 2)}/${item.hash}", file, item.hash, item.size)
                        }
                    } catch (Exception e) {
                        failed.add("${item.path}: ${e.message}")
                    }
                    def count = done.incrementAndGet()
                    if (count % 200 == 0) logger.lifecycle("  ${count}/${total} assets checked")
                }
            }
            Math.max(1, getConnections().get()).times { pool.submit(worker as Runnable) }
            pool.shutdown()
            if (!pool.awaitTermination(30, TimeUnit.MINUTES)) {
                pool.shutdownNow()
                throw new GradleException("Timed out downloading assets")
            }
        } finally {
            pool.shutdownNow()
        }

        if (!failed.isEmpty())
            throw new GradleException("${failed.size()} asset object(s) could not be downloaded:\n  " +
                    failed.take(20).join('\n  '))
        logger.lifecycle("Assets ready in ${root}: ${total} objects, ${skipped} were already present")
    }

    private Map version() {
        new ZipFile(getMetadata().get().asFile).withCloseable { zip ->
            def entry = zip.getEntry('minecraft/version.json') ?: zip.entries().find {
                !it.directory && it.name.endsWith('.json')
            }
            if (entry == null) throw new GradleException("${getMetadata().get().asFile} has no version json")
            new JsonSlurper().parse(zip.getInputStream(entry))
        }
    }

    /** Fetches one file, verifying size and sha1. Retries a few times before giving up; a '.part' file never survives. */
    void fetch(String url, File target, String expected, long size) {
        target.parentFile.mkdirs()
        File part = new File(target.parentFile, target.name + '.part')
        Exception last = null
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                part.delete()
                new URL(url).withInputStream { input -> part.withOutputStream { output -> output << input } }
                if (size > 0 && part.length() != size)
                    throw new IOException("size mismatch, expected ${size} got ${part.length()}")
                String actual = sha1Of(part)
                if (actual != expected)
                    throw new IOException("sha1 mismatch, expected ${expected} got ${actual}")
                if (!part.renameTo(target)) {
                    target.delete()
                    if (!part.renameTo(target)) throw new IOException("could not move ${part} to ${target}")
                }
                return
            } catch (Exception e) {
                last = e
                part.delete()
                if (attempt < 3) Thread.sleep(500L * attempt)
            }
        }
        throw new GradleException("Download failed: ${url} (${last?.message})", last)
    }

    static String sha1Of(File file) {
        MessageDigest digest = MessageDigest.getInstance('SHA-1')
        file.withInputStream { input ->
            byte[] buffer = new byte[65536]
            int read
            while ((read = input.read(buffer)) > 0) digest.update(buffer, 0, read)
        }
        return digest.digest().collect { String.format('%02x', it) }.join()
    }
}
