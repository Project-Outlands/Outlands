package net.minecraftforge.client.ime;

import static org.lwjgl.sdl.SDLProperties.SDL_GetPointerProperty;
import static org.lwjgl.sdl.SDLVideo.SDL_GetWindowProperties;
import static org.lwjgl.sdl.SDLVideo.SDL_PROP_WINDOW_WIN32_HWND_POINTER;

import com.sun.jna.Function;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinNT.HRESULT;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;
import org.lwjgl.MainThread;
import org.lwjgl.system.Platform;

final class WindowsImeFocus implements AutoCloseable {

    private interface Msctf extends StdCallLibrary {
        Msctf INSTANCE = Native.load("msctf", Msctf.class);

        HRESULT TF_CreateThreadMgr(PointerByReference threadManager);
    }

    private interface User32 extends StdCallLibrary {
        User32 INSTANCE = Native.load("user32", User32.class);

        HWND GetFocus();
    }

    private static final int RELEASE = 2;
    private static final int ACTIVATE = 3;
    private static final int DEACTIVATE = 4;
    private static final int CREATE_DOCUMENT_MGR = 5;
    private static final int GET_FOCUS = 7;
    private static final int SET_FOCUS = 8;
    private static final int ASSOCIATE_FOCUS = 9;

    private final HWND window;
    private final Pointer threadManager;
    private Pointer document;
    private Pointer previousAssociation;
    private Pointer previousFocus;
    private boolean activated;
    private boolean associated;

    private WindowsImeFocus(HWND window, Pointer threadManager) {
        this.window = window;
        this.threadManager = threadManager;
    }

    static WindowsImeFocus suppress(long sdlWindow) {
        if (!MainThread.isMainThread()) {
            throw new IllegalStateException("Windows IME focus must be changed on the SDL thread");
        }
        if (Platform.get() != Platform.WINDOWS) {
            throw new IllegalStateException("Windows IME focus is only available on Windows");
        }
        final long hwnd = SDL_GetPointerProperty(SDL_GetWindowProperties(sdlWindow), SDL_PROP_WINDOW_WIN32_HWND_POINTER, 0);
        if (hwnd == 0) {
            throw new IllegalStateException("The SDL window has no Win32 handle");
        }
        final PointerByReference pointer = new PointerByReference();
        check(Msctf.INSTANCE.TF_CreateThreadMgr(pointer).intValue(), "TF_CreateThreadMgr");
        if (pointer.getValue() == null) {
            throw new IllegalStateException("TF_CreateThreadMgr returned no thread manager");
        }
        final WindowsImeFocus focus = new WindowsImeFocus(new HWND(Pointer.createConstant(hwnd)), pointer.getValue());
        try {
            focus.suppress();
            return focus;
        } catch (RuntimeException | LinkageError failure) {
            try {
                focus.close();
            } catch (RuntimeException | LinkageError closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    private void suppress() {
        check(invoke(threadManager, ACTIVATE, new IntByReference()), "Activate");
        activated = true;
        final PointerByReference result = new PointerByReference();
        check(invoke(threadManager, CREATE_DOCUMENT_MGR, result), "CreateDocumentMgr");
        document = result.getValue();
        if (document == null) {
            throw new IllegalStateException("CreateDocumentMgr returned no document manager");
        }
        final boolean focused = window.equals(User32.INSTANCE.GetFocus());
        if (focused) {
            previousFocus = currentDocument();
        }
        // An empty document gives the IME no context in which to start composition.
        previousAssociation = associate(document);
        associated = true;
        if (focused) {
            check(invoke(threadManager, SET_FOCUS, document), "SetFocus");
        }
    }

    @Override
    public void close() {
        try {
            if (associated) {
                release(associate(previousAssociation));
                final Pointer restoreFocus = previousFocus != null ? previousFocus : previousAssociation;
                if (window.equals(User32.INSTANCE.GetFocus())) {
                    check(invoke(threadManager, SET_FOCUS, restoreFocus), "SetFocus");
                }
            }
        } finally {
            try {
                if (activated) {
                    check(invoke(threadManager, DEACTIVATE), "Deactivate");
                }
            } finally {
                release(previousFocus);
                release(previousAssociation);
                release(document);
                release(threadManager);
            }
        }
    }

    private Pointer currentDocument() {
        final PointerByReference result = new PointerByReference();
        check(invoke(threadManager, GET_FOCUS, result), "GetFocus");
        return result.getValue();
    }

    private Pointer associate(Pointer target) {
        final PointerByReference previous = new PointerByReference();
        check(invoke(threadManager, ASSOCIATE_FOCUS, window, target, previous), "AssociateFocus");
        return previous.getValue();
    }

    private static void release(Pointer reference) {
        if (reference != null) {
            invoke(reference, RELEASE);
        }
    }

    private static int invoke(Pointer object, int slot, Object... arguments) {
        final Object[] nativeArguments = new Object[arguments.length + 1];
        nativeArguments[0] = object;
        System.arraycopy(arguments, 0, nativeArguments, 1, arguments.length);
        final Pointer method = object.getPointer(0).getPointer((long) slot * Native.POINTER_SIZE);
        return Function.getFunction(method, Function.ALT_CONVENTION).invokeInt(nativeArguments);
    }

    private static void check(int result, String operation) {
        if (result < 0) {
            throw new IllegalStateException("ITfThreadMgr.%s failed with HRESULT 0x%08X".formatted(operation, result));
        }
    }
}
