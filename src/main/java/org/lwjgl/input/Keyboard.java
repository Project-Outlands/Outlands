package org.lwjgl.input;

import java.nio.ByteBuffer;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraftforge.fml.common.FMLLog;
import org.apache.commons.lang3.StringUtils;
import org.lwjgl.sdl.SDLKeyboard;
import org.lwjgl.LWJGLException;
import org.lwjgl.MainThread;
import org.lwjgl.Sys;
import org.lwjgl.opengl.Display;

public class Keyboard {

    /**
     * The special character meaning that no character was translated for the event.
     */
    public static final int CHAR_NONE = '\0';

    /**
     * The special keycode meaning that only the translated character is valid.
     */
    public static final int KEY_NONE = 0x00;

    public static final int KEY_ESCAPE = 0x01;
    public static final int KEY_1 = 0x02;
    public static final int KEY_2 = 0x03;
    public static final int KEY_3 = 0x04;
    public static final int KEY_4 = 0x05;
    public static final int KEY_5 = 0x06;
    public static final int KEY_6 = 0x07;
    public static final int KEY_7 = 0x08;
    public static final int KEY_8 = 0x09;
    public static final int KEY_9 = 0x0A;
    public static final int KEY_0 = 0x0B;
    public static final int KEY_MINUS = 0x0C; /* - on main keyboard */
    public static final int KEY_EQUALS = 0x0D;
    public static final int KEY_BACK = 0x0E; /* backspace */
    public static final int KEY_TAB = 0x0F;
    public static final int KEY_Q = 0x10;
    public static final int KEY_W = 0x11;
    public static final int KEY_E = 0x12;
    public static final int KEY_R = 0x13;
    public static final int KEY_T = 0x14;
    public static final int KEY_Y = 0x15;
    public static final int KEY_U = 0x16;
    public static final int KEY_I = 0x17;
    public static final int KEY_O = 0x18;
    public static final int KEY_P = 0x19;
    public static final int KEY_LBRACKET = 0x1A;
    public static final int KEY_RBRACKET = 0x1B;
    public static final int KEY_RETURN = 0x1C; /* Enter on main keyboard */
    public static final int KEY_LCONTROL = 0x1D;
    public static final int KEY_A = 0x1E;
    public static final int KEY_S = 0x1F;
    public static final int KEY_D = 0x20;
    public static final int KEY_F = 0x21;
    public static final int KEY_G = 0x22;
    public static final int KEY_H = 0x23;
    public static final int KEY_J = 0x24;
    public static final int KEY_K = 0x25;
    public static final int KEY_L = 0x26;
    public static final int KEY_SEMICOLON = 0x27;
    public static final int KEY_APOSTROPHE = 0x28;
    public static final int KEY_GRAVE = 0x29; /* accent grave */
    public static final int KEY_LSHIFT = 0x2A;
    public static final int KEY_BACKSLASH = 0x2B;
    public static final int KEY_Z = 0x2C;
    public static final int KEY_X = 0x2D;
    public static final int KEY_C = 0x2E;
    public static final int KEY_V = 0x2F;
    public static final int KEY_B = 0x30;
    public static final int KEY_N = 0x31;
    public static final int KEY_M = 0x32;
    public static final int KEY_COMMA = 0x33;
    public static final int KEY_PERIOD = 0x34; /* . on main keyboard */
    public static final int KEY_SLASH = 0x35; /* / on main keyboard */
    public static final int KEY_RSHIFT = 0x36;
    public static final int KEY_MULTIPLY = 0x37; /* * on numeric keypad */
    public static final int KEY_LMENU = 0x38; /* left Alt */
    public static final int KEY_SPACE = 0x39;
    public static final int KEY_CAPITAL = 0x3A;
    public static final int KEY_F1 = 0x3B;
    public static final int KEY_F2 = 0x3C;
    public static final int KEY_F3 = 0x3D;
    public static final int KEY_F4 = 0x3E;
    public static final int KEY_F5 = 0x3F;
    public static final int KEY_F6 = 0x40;
    public static final int KEY_F7 = 0x41;
    public static final int KEY_F8 = 0x42;
    public static final int KEY_F9 = 0x43;
    public static final int KEY_F10 = 0x44;
    public static final int KEY_NUMLOCK = 0x45;
    public static final int KEY_SCROLL = 0x46; /* Scroll Lock */
    public static final int KEY_NUMPAD7 = 0x47;
    public static final int KEY_NUMPAD8 = 0x48;
    public static final int KEY_NUMPAD9 = 0x49;
    public static final int KEY_SUBTRACT = 0x4A; /* - on numeric keypad */
    public static final int KEY_NUMPAD4 = 0x4B;
    public static final int KEY_NUMPAD5 = 0x4C;
    public static final int KEY_NUMPAD6 = 0x4D;
    public static final int KEY_ADD = 0x4E; /* + on numeric keypad */
    public static final int KEY_NUMPAD1 = 0x4F;
    public static final int KEY_NUMPAD2 = 0x50;
    public static final int KEY_NUMPAD3 = 0x51;
    public static final int KEY_NUMPAD0 = 0x52;
    public static final int KEY_DECIMAL = 0x53; /* . on numeric keypad */
    public static final int KEY_F11 = 0x57;
    public static final int KEY_F12 = 0x58;
    public static final int KEY_F13 = 0x64; /* (NEC PC98) */
    public static final int KEY_F14 = 0x65; /* (NEC PC98) */
    public static final int KEY_F15 = 0x66; /* (NEC PC98) */
    public static final int KEY_F16 = 0x67; /* Extended Function keys - (Mac) */
    public static final int KEY_F17 = 0x68;
    public static final int KEY_F18 = 0x69;
    public static final int KEY_KANA = 0x70; /* (Japanese keyboard) */
    public static final int KEY_F19 = 0x71; /* Extended Function keys - (Mac) */
    public static final int KEY_CONVERT = 0x79; /* (Japanese keyboard) */
    public static final int KEY_NOCONVERT = 0x7B; /* (Japanese keyboard) */
    public static final int KEY_YEN = 0x7D; /* (Japanese keyboard) */
    public static final int KEY_NUMPADEQUALS = 0x8D; /* = on numeric keypad (NEC PC98) */
    public static final int KEY_CIRCUMFLEX = 0x90; /* (Japanese keyboard) */
    public static final int KEY_AT = 0x91; /* (NEC PC98) */
    public static final int KEY_COLON = 0x92; /* (NEC PC98) */
    public static final int KEY_UNDERLINE = 0x93; /* (NEC PC98) */
    public static final int KEY_KANJI = 0x94; /* (Japanese keyboard) */
    public static final int KEY_STOP = 0x95; /* (NEC PC98) */
    public static final int KEY_AX = 0x96; /* (Japan AX) */
    public static final int KEY_UNLABELED = 0x97; /* (J3100) */
    public static final int KEY_NUMPADENTER = 0x9C; /* Enter on numeric keypad */
    public static final int KEY_RCONTROL = 0x9D;
    public static final int KEY_SECTION = 0xA7; /* Section symbol (Mac) */
    public static final int KEY_NUMPADCOMMA = 0xB3; /* , on numeric keypad (NEC PC98) */
    public static final int KEY_DIVIDE = 0xB5; /* / on numeric keypad */
    public static final int KEY_SYSRQ = 0xB7;
    public static final int KEY_RMENU = 0xB8; /* right Alt */
    public static final int KEY_FUNCTION = 0xC4; /* Function (Mac) */
    public static final int KEY_PAUSE = 0xC5; /* Pause */
    public static final int KEY_HOME = 0xC7; /* Home on arrow keypad */
    public static final int KEY_UP = 0xC8; /* UpArrow on arrow keypad */
    public static final int KEY_PRIOR = 0xC9; /* PgUp on arrow keypad */
    public static final int KEY_LEFT = 0xCB; /* LeftArrow on arrow keypad */
    public static final int KEY_RIGHT = 0xCD; /* RightArrow on arrow keypad */
    public static final int KEY_END = 0xCF; /* End on arrow keypad */
    public static final int KEY_DOWN = 0xD0; /* DownArrow on arrow keypad */
    public static final int KEY_NEXT = 0xD1; /* PgDn on arrow keypad */
    public static final int KEY_INSERT = 0xD2; /* Insert on arrow keypad */
    public static final int KEY_DELETE = 0xD3; /* Delete on arrow keypad */
    public static final int KEY_CLEAR = 0xDA; /* Clear key (Mac) */
    public static final int KEY_LMETA = 0xDB; /* Left Windows/Option key */
    public static final int KEY_LWIN = KEY_LMETA; /* Left Windows key */
    public static final int KEY_RMETA = 0xDC; /* Right Windows/Option key */
    public static final int KEY_RWIN = KEY_RMETA; /* Right Windows key */
    public static final int KEY_APPS = 0xDD; /* AppMenu key */
    public static final int KEY_POWER = 0xDE;
    public static final int KEY_SLEEP = 0xDF;

    public static final int keyCount;
        
    private static final Map<String, Integer> reverseKeyMap = new ConcurrentHashMap<>();
    
    private static final Queue<KeyEvent> queue = new ArrayBlockingQueue<>(128);

    private static long lastDroppedEventMs = 0L;

    /** Off until a text field asks for it: LWJGL2 delivers no repeats by default, and vanilla acts on every
     * key event it is given. A screen that wants auto-repeat calls {@link #enableRepeatEvents(boolean)}. */
    private static boolean doRepeatEvents = false;

    public static final int KEYBOARD_SIZE = Short.MAX_VALUE;

    /** Snapshot of SDL's keyboard state array, refreshed by the event pump. */
    public static ByteBuffer sdlKeyPressedArray = null;

    private static final String[] unlocalizedKeyNameMiniLut = new String[Short.MAX_VALUE];

    /**
     * Names SDL resolved, indexed by LWJGL2 keycode.
     *
     * <p>Written on the main thread only, read from any thread. An element may still be null, and a reader may
     * see a stale null; both fall back to {@link #unlocalizedKeyNameMiniLut}, which is a name rather than an
     * error.</p>
     */
    private static final String[] sdlKeyNames = new String[Short.MAX_VALUE];

    static {
        // Use reflection to find out key names
        Field[] fields = Keyboard.class.getFields();
        int keyCounter = 0;
        try {
            for (Field field : fields) {
                if (Modifier.isStatic(field.getModifiers()) && Modifier.isPublic(field.getModifiers())
                        && Modifier.isFinal(field.getModifiers())
                        && field.getType().equals(int.class)
                        && field.getName().startsWith("KEY_")
                        && !field.getName().endsWith("WIN")) {
                    /* Don't use deprecated names */
                    int key = field.getInt(null);
                    String name = field.getName().substring(4);
                    unlocalizedKeyNameMiniLut[key] = name;
                    reverseKeyMap.put(name, key);
                    keyCounter++;
                }
            }
        } catch (Exception e) {}
        keyCount = keyCounter;
        for (int i = 0; i < unlocalizedKeyNameMiniLut.length; i++) {
            if (unlocalizedKeyNameMiniLut[i] == null) {
                unlocalizedKeyNameMiniLut[i] = "Key " + i;
            }
        }
        enqueue(new KeyEvent(0, '\0', KeyState.RELEASE, Sys.getNanoTime()));
    }

    /** Populates the key name->index lookup table with the current keyboard layout based names. */
    public static void populateKeyLookupTables() {
        for (int key = 0; key <= 255; key++) {
            getKeyName(key);
        }
    }

    public static void addKeyEvent(int key, boolean pressed) {
        InputState.update(key, pressed);
        enqueue(new KeyEvent(key, '\0', pressed ? KeyState.PRESS : KeyState.RELEASE, Sys.getNanoTime()));
    }

    /**
     * Records a key event already translated to an LWJGL2 keycode.
     *
     * @param key      LWJGL2 keycode
     * @param c        the character produced, or {@code '\0'}
     * @param state    press, release or repeat
     */
    public static void addSdlKeyEvent(int key, char c, KeyState state) {
        addSdlKeyEvent(key, c, state, 0, (short) 0);
    }

    /**
     * Records a key event already translated to an LWJGL2 keycode, keeping the physical key and the modifier state
     * that SDL reported with it.
     *
     * @param key      LWJGL2 keycode
     * @param c        the character produced, or {@code '\0'}
     * @param state    press, release or repeat
     * @param scancode LWJGL2 scan code of the physical key
     * @param mods     {@code SDL_KMOD_*} bits held when the key changed
     */
    public static void addSdlKeyEvent(int key, char c, KeyState state, int scancode, short mods) {
        if (state == KeyState.REPEAT && !doRepeatEvents) {
            return;
        }
        InputState.update(key, state.isPressed);
        enqueue(new KeyEvent(key, c, state, Sys.getNanoTime(), scancode, mods));
    }

    /**
     * Queues a caller-built event. The queue keeps a copy carrying the snapshot of this enqueue, so the
     * caller's instance and any earlier copy already queued keep their own event-time state.
     *
     * @param event the event to queue
     */
    public static void addKeyEvent(KeyEvent event) {
        InputState.update(event.key, event.state.isPressed);
        enqueue(new KeyEvent(event.key, event.aChar, event.state, event.nano, InputSnapshot.current()));
    }

    /**
     * Queues a key-less character event, the shape LWJGL2 produced for text that has no key press behind it.
     * GuiScreen calls keyTyped for these, which is how text committed by an input method reaches a text field.
     */
    public static void addCharEvent(int key, char c) {
        enqueue(new KeyEvent(KEY_NONE, c, KeyState.PRESS, Sys.getNanoTime()));
    }

    private static void enqueue(KeyEvent event) {
        synchronized (queue) {
            while (!queue.offer(event)) {
                warnDroppedEvent();
                if (!dropSecondOldest()) {
                    throw new IllegalStateException("Full keyboard event queue has no pending event to drop");
                }
            }
        }
    }

    private static boolean dropSecondOldest() {
        final Object[] events = queue.toArray();
        return events.length >= 2 && queue.remove(events[1]);
    }

    /** Logs the drop at most once per second; a full queue is reported per burst, not per event. */
    private static void warnDroppedEvent() {
        final long now = System.currentTimeMillis();
        if (now - lastDroppedEventMs < 1000L) {
            return;
        }
        lastDroppedEventMs = now;
        FMLLog.log.warn("Dropping an LWJGL key event because the queue is full");
    }

    public static void create() throws LWJGLException {}

    public static boolean isKeyDown(int key) {
        final ByteBuffer state = sdlKeyPressedArray;
        if (state == null) {
            return false;
        }
        final int scancode = KeyCodes.lwjglToSdlScancode(key);
        return scancode > 0 && scancode < state.capacity() && state.get(scancode) != 0;
    }

    public static void poll() {
        // no-op
    }

    public static void enableRepeatEvents(boolean enable) {
        doRepeatEvents = enable;
    }

    public static boolean areRepeatEventsEnabled() {
        return doRepeatEvents;
    }

    public static int getKeyCount() {
        return keyCount;
    }

    public static int getNumKeyboardEvents() {
        return queue.size();
    }

    public static boolean isRepeatEvent() {
        return queue.peek().state == KeyState.REPEAT;
    }

    public static boolean next() {
        synchronized (queue) {
            boolean next = queue.size() > 1;
            if (next) {
                queue.remove();
            }
            return next;
        }
    }

    public static int getEventKey() {
        return queue.peek().key;
    }

    public static char getEventCharacter() {
        return queue.peek().aChar;
    }

    public static boolean getEventKeyState() {
        return queue.peek().state.isPressed;
    }

    public static long getEventNanoseconds() {
        return queue.peek().nano;
    }

    /**
     * Returns the input state captured when the current event was queued.
     *
     * @return an immutable event-time snapshot; later input does not change it. The queue always holds a
     *         placeholder event, so this is never null, but it only describes a real event after
     *         {@link #next()} returned true.
     */
    public static InputSnapshot getEventSnapshot() {
        return queue.peek().snapshot;
    }

    /** LWJGL2 scan code of the key the queue is currently on; {@code 0} when the event did not carry one. */
    public static int getEventScancode() {
        return queue.peek().scancode;
    }

    /**
     * {@code SDL_KMOD_*} bits that were held when the key the queue is currently on changed. Reading it here rather
     * than calling {@link #isKeyDown(int)} keeps a chord correct even if the modifier is released before the game
     * gets to the event.
     */
    public static short getEventModifiers() {
        return queue.peek().mods;
    }

    /**
     * Resolves the layout name of an LWJGL2 keycode.
     *
     * <p><b>Why this must not marshal on a miss.</b> SDL key queries are main-thread only, and the first version
     * dispatched every call there and waited for the answer. That is a blocking cross-thread wait inside a name
     * getter, and mods call name getters from wherever they happen to be: IC2C asks for key names from the
     * integrated server thread while it builds the tooltip search tree, once per tooltip line. Each of those calls
     * waited for the main thread's next frame, which during a world load is one every 200 ms - measured as a world
     * load that never finished rather than as a slow one. A name lookup therefore answers from the built-in table
     * when it is not on the main thread, and only the main thread talks to SDL.</p>
     */
    public static String getKeyName(int key) {
        if (key == KEY_NONE) {
            return "NONE";
        }
        final String name = resolveKeyName(key);
        reverseKeyMap.put(name, key);
        return name;
    }

    private static String resolveKeyName(int key) {
        final boolean resolvable = key >= 0 && key < sdlKeyNames.length;
        if (resolvable) {
            final String cached = sdlKeyNames[key];
            if (cached != null) {
                return cached;
            }
        }
        if (MainThread.isMainThread()) {
            // Resolve through the physical key so the name follows the layout without depending on the event keycode.
            final int scancode = KeyCodes.lwjglToSdlScancode(key);
            if (scancode > 0) {
                final int sdlKey = SDLKeyboard.SDL_GetKeyFromScancode(scancode, (short) 0, true);
                final String sdlName = sdlKey != 0
                        ? StringUtils.toRootUpperCase(SDLKeyboard.SDL_GetKeyName(sdlKey))
                        : null;
                if (sdlName != null && !sdlName.isEmpty()) {
                    if (resolvable) {
                        sdlKeyNames[key] = sdlName;
                    }
                    return sdlName;
                }
            }
        }
        return key >= 0 && key < unlocalizedKeyNameMiniLut.length ? unlocalizedKeyNameMiniLut[key] : "Key " + key;
    }

    public static int getKeyIndex(String keyName) {
        if (keyName.equals("NONE")) {
            return KEY_NONE;
        }
        Integer ret = reverseKeyMap.get(keyName);
        if (ret == null) {
            if (keyName.matches("Key -?[0-9]+]")) {
                return Integer.parseInt(StringUtils.removeStart(keyName, "Key "));
            }
            return KEY_NONE;
        } else return ret;
    }

    public static boolean isCreated() {
        return Display.isCreated();
    }

    public static void destroy() {}
}
