package net.minecraftforge.fml.common;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.event.FMLEvent;
import net.minecraftforge.fml.common.eventhandler.Event;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.IEventListener;

/**
 * The handle an entry point registers its listeners through.
 *
 * <p>There are two event paths in this loader and a declarative mod needs both, which is why this class has two
 * methods rather than one:
 *
 * <ul>
 *   <li>{@link #onPhase} — the per-mod bus. Lifecycle events ({@code FMLPreInitializationEvent} and the rest) are posted
 *       to a channel that belongs to one mod, and subscribing there is what makes a container "active".</li>
 *   <li>{@link #listen} — the shared Forge bus, where gameplay events such as {@code RegistryEvent} are posted.</li>
 * </ul>
 *
 * <p>Both take a {@link Consumer} because the whole point of a declarative entry point is that the mod has no methods for
 * {@code @Subscribe}/{@code @SubscribeEvent} to sit on — a Clojure namespace or a JavaScript module has no methods to
 * annotate. A Java entry point writes a lambda; another runtime binds the same functional interface.
 */
public final class EntrypointRegistrar {

    private final ModContainer owner;
    /** Handlers for the per-mod channel, in registration order, dispatched by type. */
    private final Map<Class<? extends FMLEvent>, List<Consumer<FMLEvent>>> phaseHandlers = new LinkedHashMap<>();
    private final List<IEventListener> globalListeners = new ArrayList<>();

    EntrypointRegistrar(ModContainer owner) {
        this.owner = owner;
    }

    /** Registers {@code listener} for a lifecycle event delivered to this mod's own channel. */
    public <T extends FMLEvent> void onPhase(Class<T> type, Consumer<T> listener) {
        registerPhase(type, listener);
    }

    /** Registers {@code listener} for a gameplay event on the shared Forge bus, at the default priority. */
    public <T extends Event> void listen(Class<T> type, Consumer<T> listener) {
        listen(type, EventPriority.NORMAL, listener);
    }

    /**
     * Registers {@code listener} for a gameplay event on the shared Forge bus.
     *
     * <p>The owner is passed along so an exception is attributed to this mod rather than to whichever mod happened to be
     * active at the time.
     */
    public <T extends Event> void listen(Class<T> type, EventPriority priority, Consumer<T> listener) {
        if (type == null) {
            throw new IllegalArgumentException("A declarative listener must name the event type it handles");
        }
        if (listener == null) {
            throw new IllegalArgumentException("Cannot register a null listener for " + type.getName());
        }
        IEventListener wrapper = new TypedConsumerListener<>(type, listener);
        MinecraftForge.EVENT_BUS.addListener(type, priority, wrapper, owner);
        globalListeners.add(wrapper);
    }

    private <T extends FMLEvent> void registerPhase(Class<T> type, Consumer<T> listener) {
        if (type == null) {
            throw new IllegalArgumentException("A declarative phase listener must name the event type it handles");
        }
        if (listener == null) {
            throw new IllegalArgumentException("Cannot register a null listener for " + type.getName());
        }
        phaseHandlers.computeIfAbsent(type, key -> new ArrayList<>()).add(event -> listener.accept(type.cast(event)));
    }

    /**
     * Delivers a lifecycle event to the handlers registered for its type.
     *
     * <p>Walks the event's own class hierarchy so a handler for {@code FMLEvent} sees every phase: a mod that wants "tell
     * me whenever a phase happens" should not have to enumerate them.
     */
    void dispatchPhase(FMLEvent event) {
        if (phaseHandlers.isEmpty()) {
            return;
        }
        for (Class<?> type = event.getClass(); type != null; type = type.getSuperclass()) {
            List<Consumer<FMLEvent>> handlers = phaseHandlers.get(type);
            if (handlers != null) {
                for (Consumer<FMLEvent> handler : handlers) {
                    handler.accept(event);
                }
            }
        }
    }

    boolean hasPhaseHandlers() {
        return !phaseHandlers.isEmpty();
    }

    /** @return the gameplay listeners registered through this registrar, in registration order */
    List<IEventListener> listeners() {
        return Collections.unmodifiableList(globalListeners);
    }

    /**
     * A listener with no method to inspect, so it checks the type itself.
     *
     * <p>Registration already ties it to one event type's list, so a mismatch means the listener lists were wired wrong.
     * Saying so beats a {@code ClassCastException} inside a mod's handler.
     */
    private static final class TypedConsumerListener<T extends Event> implements IEventListener {

        private final Class<T> type;
        private final Consumer<T> listener;

        TypedConsumerListener(Class<T> type, Consumer<T> listener) {
            this.type = type;
            this.listener = listener;
        }

        @Override
        public void invoke(Event event) {
            if (!type.isInstance(event)) {
                throw new IllegalStateException("A listener for " + type.getName() + " received a "
                        + (event == null ? "null event" : event.getClass().getName()));
            }
            listener.accept(type.cast(event));
        }

        @Override
        public String toString() {
            return "DeclarativeListener[" + type.getName() + "]";
        }
    }
}
