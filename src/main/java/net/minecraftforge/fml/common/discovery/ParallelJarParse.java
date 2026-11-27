/*
 * Copyright (c) 2026 Outlands contributors
 *
 * This file is licensed under the Outlands License Version 1.0.
 * See the applicable LICENSE file in this directory or a parent directory
 * for the full licence terms.
 */

package net.minecraftforge.fml.common.discovery;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import javax.annotation.Nullable;

import net.minecraftforge.fml.common.discovery.asm.ASMModParser;

/**
 * Parses the scanned entries of one jar on several threads, keeping scan order.
 */
public final class ParallelJarParse
{
    /** One entry's outcome: the parser, or the failure that stopped it. */
    public record Parsed(@Nullable ASMModParser parser, @Nullable Exception failure) {}

    /** Below this many entries the thread hand-off costs more than it saves. */
    private static final int MIN_ENTRIES_PER_THREAD = 32;

    private static final int THREADS = Runtime.getRuntime().availableProcessors();

    /** Startup-only burst pool; idle threads time out, so nothing is left running after discovery. */
    private static final ExecutorService POOL = createPool();

    private ParallelJarParse() {}

    private static ExecutorService createPool()
    {
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "Outlands jar scan");
            thread.setDaemon(true);
            return thread;
        };
        ThreadPoolExecutor executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(Math.max(1, THREADS), factory);
        executor.setKeepAliveTime(30L, TimeUnit.SECONDS);   // nonzero before core threads may time out
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    /**
     * Parses {@code entries} (the scanned subset of a jar's central directory, in order) and returns one result per
     * entry, in the same order. Entries are split into contiguous chunks, so the caller can keep walking the result
     * serially.
     */
    public static List<Parsed> parse(List<JarEntry> entries, JarFile jar)
    {
        int count = entries.size();
        int threads = Math.clamp(count / MIN_ENTRIES_PER_THREAD, 1, THREADS);
        if (threads <= 1)
        {
            List<Parsed> sequential = new ArrayList<>(count);
            for (JarEntry entry : entries)
            {
                sequential.add(parseOne(entry, jar));
            }
            return sequential;
        }

        int perChunk = (count + threads - 1) / threads;
        List<Future<List<Parsed>>> chunks = new ArrayList<>(threads);
        for (int start = 0; start < count; start += perChunk)
        {
            int from = start;
            int to = Math.min(count, start + perChunk);
            chunks.add(POOL.submit(() -> parseChunk(entries, from, to, jar)));
        }

        List<Parsed> parsed = new ArrayList<>(count);
        try
        {
            for (Future<List<Parsed>> chunk : chunks)
            {
                parsed.addAll(chunk.get());
            }
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
        catch (ExecutionException e)
        {
            // parseOne never throws, so a chunk can only fail on something unexpected
            throw new IllegalStateException("parallel jar parse failed", e.getCause());
        }
        return parsed;
    }

    private static List<Parsed> parseChunk(List<JarEntry> entries, int from, int to, JarFile jar)
    {
        List<Parsed> chunk = new ArrayList<>(to - from);
        for (int i = from; i < to; i++)
        {
            chunk.add(parseOne(entries.get(i), jar));
        }
        return chunk;
    }

    private static Parsed parseOne(JarEntry entry, JarFile jar)
    {
        try (InputStream in = jar.getInputStream(entry))
        {
            return new Parsed(new ASMModParser(read(entry, in)), null);
        }
        catch (Exception e)
        {
            return new Parsed(null, e);
        }
    }

    /**
     * Reads the class file into one array of the size the central directory already knows.
     *
     * <p>Handing the stream to ASM instead made it copy the class into a growing buffer and then copy it again for the
     * bytes: 313 MB of allocation inside the parser over a measured start, most of it those two copies of data the jar
     * had already described.
     */
    private static byte[] read(JarEntry entry, InputStream in) throws IOException
    {
        final long size = entry.getSize();

        if (size < 0 || size > Integer.MAX_VALUE - 8)
        {
            return in.readAllBytes();
        }

        final byte[] bytes = new byte[(int) size];
        int filled = 0;

        while (filled < bytes.length)
        {
            final int read = in.read(bytes, filled, bytes.length - filled);

            if (read < 0)
            {
                break;
            }

            filled += read;
        }

        return filled == bytes.length ? bytes : java.util.Arrays.copyOf(bytes, filled);
    }
}
