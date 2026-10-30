/*
 * Minecraft Forge
 * Copyright (c) 2016-2020.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation version 2.1
 * of the License.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */

package net.minecraftforge.fml.common.eventhandler;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import net.minecraftforge.fml.common.FMLLog;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.ModContainer;

import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.MapMaker;
import com.google.common.collect.Sets;
import com.google.common.reflect.TypeToken;
import org.jspecify.annotations.NonNull;

public class EventBus implements IEventExceptionHandler
{
    private static int maxID = 0;

    private ConcurrentHashMap<Object, ArrayList<IEventListener>> listeners = new ConcurrentHashMap<Object, ArrayList<IEventListener>>();
    private Map<Object,ModContainer> listenerOwners = new MapMaker().weakKeys().weakValues().makeMap();
    private final int busID = maxID++;
    private IEventExceptionHandler exceptionHandler;
    private boolean shutdown;

    public EventBus()
    {
        ListenerList.resize(busID + 1);
        exceptionHandler = this;
    }

    public EventBus(@Nonnull IEventExceptionHandler handler)
    {
        this();
        Preconditions.checkNotNull(handler, "EventBus exception handler can not be null");
        exceptionHandler = handler;
    }

    public void register(Object target)
    {
        if (listeners.containsKey(target))
        {
            return;
        }

        ModContainer activeModContainer = Loader.instance().activeModContainer();
        if (activeModContainer == null)
        {
            FMLLog.log.error("Unable to determine registrant mod for {}. This is a critical error and should be impossible", target, new Throwable());
            activeModContainer = Loader.instance().getMinecraftModContainer();
        }
        listenerOwners.put(target, activeModContainer);


        Collection<Method> methods;
        if (target instanceof Class<?> clazz) {
            // static listener: subscribed methods must be declared by the class
            methods = Arrays.stream(clazz.getDeclaredMethods())
                .filter(m -> !m.isSynthetic()
                             && Modifier.isStatic(m.getModifiers())
                             // private not allowed to keep legacy behaviour
                             && !Modifier.isPrivate(m.getModifiers())
                             && m.isAnnotationPresent(SubscribeEvent.class))
                .toList();
        } else {
            // instance listener: methods overriding a subscribed method is also valid.
            //
            // In this case, we will register the subscribed parent method instead. JVM will
            // handle it if a subclass overrides subscribed method
            methods = TypeToken.of(target.getClass())
                .getTypes()
                .rawTypes()
                // get self & superclass & interface
                .stream()
                .map(Class::getDeclaredMethods)
                .flatMap(Arrays::stream)
                .filter(m -> !m.isSynthetic()
                             && !Modifier.isStatic(m.getModifiers())
                             // private not allowed because it does not participate in inheritance
                             && !Modifier.isPrivate(m.getModifiers())
                             && m.isAnnotationPresent(SubscribeEvent.class))
                // deduplicate by signature
                .collect(Collectors.toMap(
                    m -> Map.entry(m.getName(), Arrays.asList(m.getParameterTypes())),
                    Function.identity(),
                    (a, b) -> a,
                    LinkedHashMap::new
                ))
                .values();
        }

        for (Method method : methods)
        {
            var parameterTypes = method.getParameterTypes();
            if (parameterTypes.length != 1)
            {
                throw new IllegalArgumentException(
                    "Method " + method + " has @SubscribeEvent annotation, but requires " + parameterTypes.length +
                        " arguments.  Event handler methods must require a single argument."
                );
            }

            Class<?> eventType = parameterTypes[0];
            if (!Event.class.isAssignableFrom(eventType))
            {
                throw new IllegalArgumentException("Method " + method + " has @SubscribeEvent annotation, but takes a argument that is not an Event " + eventType);
            }

            register(eventType, target, method, activeModContainer);
        }
    }

    /// Some mods will intentionally call this method with illegal `method` object (whose param type is `Object` instead of subclass of `Event`), and we have to allow this stupid move
    private void register(Class<?> eventType, Object target, Method method, final ModContainer owner)
    {
        try
        {
            final ASMEventHandler asm = new ASMEventHandler(target, method, owner, IGenericEvent.class.isAssignableFrom(eventType));

            IEventListener listener = asm;
            if (IContextSetter.class.isAssignableFrom(eventType))
            {
                listener = new ContextSetterEventListener(owner, asm);
            }

            listenerListOf(eventType.asSubclass(Event.class)).register(busID, asm.getPriority(), listener);

            ArrayList<IEventListener> others = listeners.computeIfAbsent(target, k -> new ArrayList<>());
            others.add(listener);
        }
        catch (Exception e)
        {
            FMLLog.log.error("Error registering event handler: {} {} {}", owner, eventType, method, e);
        }
    }

    public void unregister(Object object)
    {
        ArrayList<IEventListener> list = listeners.remove(object);
        if(list == null)
            return;
        for (IEventListener listener : list)
        {
            ListenerList.unregisterAll(busID, listener);
        }
    }

    /**
     * Registers a listener without an annotated method, for code that cannot carry {@link SubscribeEvent} - another
     * language's entry point, or a container that only wants a few phase callbacks.
     *
     * <p>Prefer {@link #register(Object)} where it applies: this path exists for the cases where it cannot.
     * The listener is owned by the mod active at registration time, which is what shows up in context and in error
     * reporting.
     *
     * @throws IllegalArgumentException if {@code eventType} is not an event type
     */
    public void addListener(Class<? extends Event> eventType, EventPriority priority, IEventListener listener)
    {
        addListener(eventType, priority, listener, Loader.instance().activeModContainer());
    }

    /**
     * @param owner the container this listener belongs to; used for context and error attribution, and reachable
     *              again by passing the same {@code listener} to {@link #removeListener(IEventListener)}
     */
    public void addListener(Class<? extends Event> eventType, EventPriority priority, IEventListener listener, @Nullable ModContainer owner)
    {
        Preconditions.checkNotNull(eventType, "Listened event type can not be null");
        Preconditions.checkNotNull(priority, "Listener priority can not be null");
        Preconditions.checkNotNull(listener, "Listener can not be null");

        // A listener registered without a method to inspect has not been checked against the event type, so keep the
        // first call that gets it wrong inside registration instead of inside post(), where it would look like an
        // unrelated mod crashing.
        ListenerList list = listenerListOf(eventType);
        IEventListener effective = listener;
        if (IContextSetter.class.isAssignableFrom(eventType))
        {
            effective = new ContextSetterEventListener(owner, listener);
        }

        list.register(busID, priority, effective);
        listeners.computeIfAbsent(listener, k -> new ArrayList<>()).add(effective);
    }

    /**
     * Removes a listener registered through {@link #addListener}. Safe to call for a listener that was never added.
     */
    public void removeListener(IEventListener listener)
    {
        if (listener == null)
        {
            return;
        }
        unregister(listener);
    }

    /**
     * Releases every listener this bus registered.
     *
     * <p>Needed because the listener lists live on the event classes, not on the bus: dropping a bus without this
     * leaves its listeners reachable forever, and they are kept per bus id so a later bus cannot reuse the slots.
     */
    public void unregisterAll()
    {
        for (ArrayList<IEventListener> registered : listeners.values())
        {
            for (IEventListener listener : registered)
            {
                ListenerList.unregisterAll(busID, listener);
            }
        }
        listeners.clear();
    }

    /**
     * Returns the listener list of {@code eventType}.
     *
     * <p>Instantiates the event so that a handwritten {@code getListenerList()} override anywhere in the hierarchy wins
     * by virtual dispatch - the probe always builds the base implementation's per-class list, which is not the list a
     * handwritten override posts to. Classes without a public no-arg constructor can still be listened to only when
     * they use the base implementation, where the probe resolves exactly what posting will resolve.
     */
    private static ListenerList listenerListOf(Class<? extends Event> eventType)
    {
        try
        {
            Constructor<?> ctr = eventType.getConstructor();
            ctr.setAccessible(true);
            return ((Event) ctr.newInstance()).getListenerList();
        }
        catch (NoSuchMethodException e)
        {
            if (declaresOwnListenerList(eventType))
            {
                // Registering into the probe's list here would succeed and then never fire, because posting goes
                // through the override. Refusing is the only answer that does not need to manufacture an instance.
                throw new IllegalArgumentException("Event type " + eventType.getName()
                        + " overrides getListenerList() but declares no public no-arg constructor, so its own list cannot"
                        + " be reached; either drop the override or add a public no-arg constructor", e);
            }
            return EventCompatProbe.listenerList(eventType);
        }
        catch (ReflectiveOperationException e)
        {
            throw new IllegalArgumentException("Event type " + eventType.getName() + " cannot be instantiated to reach its listener list", e);
        }
    }

    /** @return whether the effective {@code getListenerList()} is anything but the base implementation */
    private static boolean declaresOwnListenerList(Class<? extends Event> eventType)
    {
        try
        {
            return eventType.getMethod("getListenerList").getDeclaringClass() != Event.class;
        }
        catch (NoSuchMethodException e)
        {
            return false;
        }
    }

    public boolean post(Event event)
    {
        if (shutdown) return false;

        IEventListener[] listeners = event.getListenerList().getListeners(busID);
        int index = 0;
        try
        {
            for (; index < listeners.length; index++)
            {
                listeners[index].invoke(event);
            }
        }
        catch (Throwable throwable)
        {
            exceptionHandler.handleException(this, event, listeners, index, throwable);
            Throwables.throwIfUnchecked(throwable);
            throw new RuntimeException(throwable);
        }
        return event.isCancelable() && event.isCanceled();
    }

    public void shutdown()
    {
        FMLLog.log.warn("EventBus {} shutting down - future events will not be posted.", busID);
        shutdown = true;
        unregisterAll();
    }

    @Override
    public void handleException(EventBus bus, Event event, IEventListener[] listeners, int index, Throwable throwable)
    {
        FMLLog.log.error("Exception caught during firing event {}:", event, throwable);
        FMLLog.log.error("Index: {} Listeners:", index);
        for (int x = 0; x < listeners.length; x++)
        {
            FMLLog.log.error("{}: {}", x, listeners[x]);
        }
    }

    private record ContextSetterEventListener(
        @Nullable ModContainer owner,
        IEventListener listener
    ) implements IEventListener {

        @Override
        public void invoke(Event e) {
            var loader = Loader.instance();
            var old = loader.activeModContainer();

            loader.setActiveModContainer(owner);
            ((IContextSetter) e).setModContainer(owner);

            listener.invoke(e);

            loader.setActiveModContainer(old);
        }

        @Override
        @NonNull
        public String toString() {
            return "ContextSetter[mod=" + (owner == null ? "" : owner.getModId()) + ", listener=" + listener + "]";
        }
    }
}
