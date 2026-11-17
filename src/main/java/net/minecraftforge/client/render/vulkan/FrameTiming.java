package net.minecraftforge.client.render.vulkan;

/**
 * Where a frame's CPU time goes, and how much of it is waiting for the GPU.
 *
 * <p><b>Why this exists.</b> Every question about moving work between threads - recording commands in parallel,
 * uploading chunk meshes off the render thread - has the same missing input: nobody has measured how much CPU time
 * a frame spends. Without it "the CPU is the bottleneck" is a guess, and so is "it is not".</p>
 *
 * <p><b>Four numbers, because they answer different questions.</b> {@code wait} is the fence wait, which is how long
 * the GPU made the CPU idle - when it is large the GPU is the limiter and no CPU work is worth moving.
 * {@code prepare} is everything between that wait and the first recorded command: releasing the previous slot's
 * staging, and the terrain layer's own frame preparation, which is where captured chunk meshes reach the arena.
 * {@code record} is building the command buffer. {@code frame} is the whole call, so
 * {@code frame - wait - prepare - record} is what is left.</p>
 */
final class FrameTiming {

    private static long waitNanos;
    private static long prepareNanos;
    private static long acquireNanos;
    private static long texturesNanos;
    private static long recordNanos;
    private static long frameNanos;
    private static long worstFrameNanos;
    private static int frames;

    private static long frameStart;
    private static long waitEnd;
    private static long prepareEnd;
    private static long acquireEnd;
    private static long texturesEnd;
    private static long recordEnd;

    private static long drawCount;
    private static int maxDrawCount;

    private FrameTiming() {}

    static void begin() {
        frameStart = System.nanoTime();
        waitEnd = 0;
        prepareEnd = 0;
        acquireEnd = 0;
        texturesEnd = 0;
        recordEnd = 0;
    }

    static void afterWait() {
        waitEnd = System.nanoTime();
    }

    static void afterPrepare() {
        prepareEnd = System.nanoTime();
    }

    static void afterAcquire() {
        acquireEnd = System.nanoTime();
    }

    static void afterTextures() {
        texturesEnd = System.nanoTime();
    }

    /** How many operations the frame handed the replay, so a slow replay can be told from a slow everything-else. */
    static void draws(int count) {
        drawCount += count;
        if (count > maxDrawCount) {
            maxDrawCount = count;
        }
    }

    static void afterRecord() {
        recordEnd = System.nanoTime();
    }

    static void end() {
        long now = System.nanoTime();
        long total = now - frameStart;
        frameNanos += total;
        if (total > worstFrameNanos) {
            worstFrameNanos = total;
        }
        if (waitEnd != 0) {
            waitNanos += waitEnd - frameStart;
        }
        long cursor = frameStart;
        if (waitEnd != 0) {
            waitNanos += waitEnd - cursor;
            cursor = waitEnd;
        }
        if (prepareEnd != 0) {
            prepareNanos += prepareEnd - cursor;
            cursor = prepareEnd;
        }
        if (acquireEnd != 0) {
            acquireNanos += acquireEnd - cursor;
            cursor = acquireEnd;
        }
        if (texturesEnd != 0) {
            texturesNanos += texturesEnd - cursor;
            cursor = texturesEnd;
        }
        if (recordEnd != 0) {
            recordNanos += recordEnd - cursor;
        }
        frames++;
    }

    static String reportAndReset() {
        if (frames == 0) {
            return "no frames";
        }
        String report = "wait " + ms(waitNanos / (double) frames)
                + " / prepare " + ms(prepareNanos / (double) frames)
                + " / acquire " + ms(acquireNanos / (double) frames)
                + " / textures " + ms(texturesNanos / (double) frames)
                + " / record " + ms(recordNanos / (double) frames)
                + " / frame " + ms(frameNanos / (double) frames)
                + " ms (worst " + ms(worstFrameNanos) + "), " + frames + " frames, "
                + (frames == 0 ? 0 : drawCount / frames) + " draws a frame (at most " + maxDrawCount + ")";
        waitNanos = 0;
        prepareNanos = 0;
        acquireNanos = 0;
        texturesNanos = 0;
        recordNanos = 0;
        drawCount = 0;
        maxDrawCount = 0;
        frameNanos = 0;
        worstFrameNanos = 0;
        frames = 0;
        return report;
    }

    private static String ms(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value / 1_000_000.0);
    }
}
