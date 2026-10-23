package org.lwjgl;

import org.lwjgl.sdl.SDL_calloc_func;
import org.lwjgl.sdl.SDL_calloc_funcI;
import org.lwjgl.sdl.SDL_free_func;
import org.lwjgl.sdl.SDL_free_funcI;
import org.lwjgl.sdl.SDL_malloc_func;
import org.lwjgl.sdl.SDL_malloc_funcI;
import org.lwjgl.sdl.SDL_realloc_func;
import org.lwjgl.sdl.SDL_realloc_funcI;
import org.lwjgl.system.MemoryUtil;

/**
 * Holds the allocator callbacks handed to {@code SDL_SetMemoryFunctions}.
 * <p>
 * SDL keeps the raw pointers for the lifetime of the process, so the callback objects have to stay
 * strongly reachable from Java. Method references passed inline would be collected and SDL would then
 * call through freed memory.
 * <p>
 * Routing SDL through LWJGL's allocator matters beyond callback lifetime: buffers SDL owns (the
 * fullscreen mode list, for instance) are released by LWJGL's PointerBuffer wrappers with
 * {@link MemoryUtil#nmemFree}, so both sides must use the same heap.
 */
final class SDLAllocator {

    private final SDL_malloc_func malloc;
    private final SDL_calloc_func calloc;
    private final SDL_realloc_func realloc;
    private final SDL_free_func free;

    private SDLAllocator() {
        this.malloc = SDL_malloc_func.create(SDLAllocator::malloc);
        this.calloc = SDL_calloc_func.create(SDLAllocator::calloc);
        this.realloc = SDL_realloc_func.create(SDLAllocator::realloc);
        this.free = SDL_free_func.create(SDLAllocator::free);
    }

    static SDLAllocator create() {
        return new SDLAllocator();
    }

    private static long malloc(long size) {
        return MemoryUtil.nmemAllocChecked(size);
    }

    private static long calloc(long count, long size) {
        return MemoryUtil.nmemCallocChecked(count, size);
    }

    private static long realloc(long ptr, long size) {
        return MemoryUtil.nmemReallocChecked(ptr, size);
    }

    private static void free(long ptr) {
        MemoryUtil.nmemFree(ptr);
    }

    SDL_malloc_funcI malloc() {
        return malloc;
    }

    SDL_calloc_funcI calloc() {
        return calloc;
    }

    SDL_realloc_funcI realloc() {
        return realloc;
    }

    SDL_free_funcI free() {
        return free;
    }
}
