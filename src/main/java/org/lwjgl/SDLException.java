package org.lwjgl;

import org.lwjgl.sdl.SDLError;

/**
 * Signals a failed SDL call.
 * <p>
 * The message is captured at construction time: SDL keeps a single global error string that the next call
 * overwrites, so reading it later is unreliable.
 */
public class SDLException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Uses the current SDL error string as the message. */
    public SDLException() {
        this(SDLError.SDL_GetError());
    }

    public SDLException(String message) {
        super(message);
    }

    public SDLException(String message, Throwable cause) {
        super(message, cause);
    }
}
