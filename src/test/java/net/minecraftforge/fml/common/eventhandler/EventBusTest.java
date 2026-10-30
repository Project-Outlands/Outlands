package net.minecraftforge.fml.common.eventhandler;

import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.ModContainer;
import net.minecraftforge.fml.common.eventhandler.impl.*;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;

/**
 * @author ZZZank
 */
public class EventBusTest {

    @Test
    public void registerInstance() {
        var bus = new EventBus();

        var listeners = new InstanceListeners();
        bus.register(listeners);

        var event = new ExampleEvent();
        bus.post(event);

        Assertions.assertEquals(event.id, listeners.recorded);
        Assertions.assertEquals(
            List.of(EventPriority.HIGHEST, EventPriority.NORMAL, EventPriority.LOWEST),
            listeners.triggered
        );
    }

    @Test
    public void registerStatic() {
        var bus = new EventBus();

        bus.register(StaticListeners.class);

        var event = new ExampleEvent();
        bus.post(event);

        Assertions.assertEquals(event.id, StaticListeners.recorded);
        Assertions.assertEquals(
            List.of(EventPriority.HIGHEST, EventPriority.NORMAL, EventPriority.LOWEST),
            StaticListeners.triggered
        );
    }

    @Test
    public void registerAbnormal() {
        var bus = new EventBus();

        bus.register(AbnormalListeners.Actual.class);

        var event = new ExampleEvent();
        bus.post(event);

        Assertions.assertTrue(AbnormalListeners.nonVoid, "listener with non-void return type is valid");
    }

    @Test
    public void registerIllegalParamType() throws Exception {
        var bus = new EventBus();

        var listener = new AbnormalListeners.IllegalParamType();

        var method = EventBus.class.getDeclaredMethod(
            "register",
            Class.class, Object.class, Method.class, ModContainer.class
        );
        method.setAccessible(true);
        method.invoke(
            bus,
            ExampleEvent.class,
            listener,
            AbnormalListeners.IllegalParamType.class.getMethod("onEvent", Object.class),
            Loader.instance().getMinecraftModContainer()
        );

        var event = new ExampleEvent();
        bus.post(event);

        Assertions.assertEquals(event.id, listener.captured);
    }

    @Test
    public void registerNonPublic() {
        var bus = new EventBus();

        // static
        {
            bus.register(NonPublicListeners.class);

            var event = new ExampleEvent();
            bus.post(event);
            Assertions.assertEquals(Set.of("packaged static", "protected static"), event.sink);

            bus.unregister(NonPublicListeners.class);
        }

        // instance
        {
            var listener = new NonPublicListeners.OverrideWithNoSub();
            bus.register(listener);

            var event = new ExampleEvent();
            bus.post(event);
            Assertions.assertEquals(Set.of("packaged", "protected (subclass)"), event.sink);

            bus.unregister(listener);
        }
    }

    @Test
    public void addListenerReceivesEventsInPriorityOrder() {
        var bus = new EventBus();
        var order = new java.util.ArrayList<String>();

        // Registered out of order on purpose: the list is ordered by priority, not by registration.
        bus.addListener(ExampleEvent.class, EventPriority.LOWEST, e -> order.add("lowest"), null);
        bus.addListener(ExampleEvent.class, EventPriority.HIGHEST, e -> order.add("highest"), null);
        bus.addListener(ExampleEvent.class, EventPriority.NORMAL, e -> order.add("normal"), null);

        bus.post(new ExampleEvent());

        Assertions.assertEquals(List.of("highest", "normal", "lowest"), order);
    }

    @Test
    public void removeListenerStopsDelivery() {
        var bus = new EventBus();
        var seen = new java.util.ArrayList<String>();
        IEventListener kept = e -> seen.add("kept");
        IEventListener removed = e -> seen.add("removed");

        bus.addListener(ExampleEvent.class, EventPriority.NORMAL, kept, null);
        bus.addListener(ExampleEvent.class, EventPriority.NORMAL, removed, null);
        bus.removeListener(removed);

        bus.post(new ExampleEvent());

        Assertions.assertEquals(List.of("kept"), seen);
    }

    @Test
    public void shutdownReleasesListeners() throws Exception {
        var bus = new EventBus();

        // Programmatic listeners are the ones that would otherwise outlive the bus: the listener list lives on the
        // event class, so nothing about dropping the bus releases them. Assert the release itself - post() being
        // blocked by the shutdown flag would pass even if nothing were ever unregistered.
        bus.addListener(ExampleEvent.class, EventPriority.NORMAL, e -> { }, null);
        Assertions.assertFalse(registeredListeners(bus).isEmpty(), "precondition: the listener was tracked");

        bus.shutdown();

        Assertions.assertTrue(registeredListeners(bus).isEmpty(),
                "shutdown must drop every listener the bus registered");
        Assertions.assertFalse(bus.post(new ExampleEvent()), "a shut down bus must not post");
    }

    private static java.util.Map<?, ?> registeredListeners(EventBus bus) throws Exception {
        var field = EventBus.class.getDeclaredField("listeners");
        field.setAccessible(true);
        return (java.util.Map<?, ?>) field.get(bus);
    }

    @Test
    public void cancelableEvent() {
        // The annotation used to be compiled into a constant-returning override by the transformer. Now the answer
        // comes from the probe, and getting it wrong would silently make every @Cancelable event non-cancelable.
        var event = new CancelableEvents.CancelableEvent();
        event.setCanceled(true);
        Assertions.assertTrue(event.isCanceled());

        var bus = new EventBus();
        Assertions.assertTrue(bus.post(event), "post returns true when the event is cancelable and canceled");
    }

    @Test
    public void nonCancelableEvent() {
        var event = new CancelableEvents.NonCancelableEvent();
        Assertions.assertThrows(
            UnsupportedOperationException.class,
            () -> event.setCanceled(true),
            "setCanceled on a non-cancelable event must throw"
        );
    }

    @Test
    public void handWrittenCancelableOverrideWins() {
        // A handwritten override must beat the annotation probe, which only answers for classes using the base impl.
        var event = new CancelableEvents.HandWritten();
        Assertions.assertTrue(event.isCancelable());
        event.setCanceled(true);
        Assertions.assertTrue(event.isCanceled());
    }

    @Test
    public void subclassEventsReachSuperclassListeners() {
        var bus = new EventBus();
        var parent = new PolymorphicEvents.ParentListener();
        var child = new PolymorphicEvents.ChildListener();
        bus.register(parent);
        bus.register(child);

        // The transformer chained each class's list to its superclass's. The probe has to do the same, or listeners
        // registered on a common base event would silently stop firing for every subclass event.
        bus.post(new PolymorphicEvents.ChildEvent());
        Assertions.assertEquals(1, parent.calls, "superclass listeners fire for subclass events");
        Assertions.assertEquals(1, child.calls);

        // One-way: a superclass event must not reach subclass listeners.
        bus.post(new PolymorphicEvents.ParentEvent());
        Assertions.assertEquals(2, parent.calls);
        Assertions.assertEquals(1, child.calls, "subclass listeners must not fire for superclass events");
    }

    @Test
    public void registersEventWithoutNoArgConstructor() {
        // Types such as TextureStitchEvent$Pre cannot be instantiated; the probe resolves what posting resolves.
        var bus = new EventBus();
        var seen = new java.util.ArrayList<String>();
        bus.addListener(ParameterlessByConstruction.class, EventPriority.NORMAL, e -> seen.add("heard"), null);

        bus.post(new ParameterlessByConstruction(7));
        Assertions.assertEquals(List.of("heard"), seen);
    }

    @Test
    public void refusesHandWrittenListThatCannotBeReached() {
        // Registering into the probe's list would succeed and then never fire, since posting goes through the
        // override. A clear failure is the only useful answer that does not need to manufacture an instance.
        var bus = new EventBus();
        var error = Assertions.assertThrows(IllegalArgumentException.class,
                () -> bus.addListener(HandwrittenListWithoutNoArgCtor.class, EventPriority.NORMAL, e -> { }, null));

        Assertions.assertTrue(error.getMessage().contains("no-arg constructor"), error.getMessage());
    }

    private static class ParameterlessByConstruction extends Event {
        ParameterlessByConstruction(@SuppressWarnings("unused") int unused) {
        }
    }

    /** Replaces the per-class list with its own and has no public no-arg constructor: unreachable by design. */
    private static class HandwrittenListWithoutNoArgCtor extends Event {
        private final ListenerList own = new ListenerList();

        HandwrittenListWithoutNoArgCtor(@SuppressWarnings("unused") int unused) {
        }

        @Override
        public ListenerList getListenerList() {
            return own;
        }
    }
}
