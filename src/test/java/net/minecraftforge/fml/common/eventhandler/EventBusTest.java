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
}
