package net.minecraftforge.fml.common;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;

import net.minecraftforge.fml.common.event.FMLEvent;
import net.minecraftforge.fml.common.eventhandler.IEventListener;

import javax.annotation.Nullable;

/**
 * A mod with no mod class: its behaviour is whatever its declared entry point registers on the event buses.
 *
 * <p>Built on {@link DummyModContainer} because that already implements the whole container contract as harmless
 * defaults, so only the three answers that matter are overridden:
 *
 * <ul>
 *   <li>{@link #getSource()} returns the real file. The server side calls {@code source.isDirectory()} while scanning
 *       resource packs, so a null source is a null pointer rather than "no resource pack".</li>
 *   <li>{@link #registerBus} returns true and loads the entry point. Returning false would leave the mod out of the
 *       active list and make the whole feature silently do nothing.</li>
 *   <li>{@link #matches} stays false: {@code @Mod.Instance} must not hand out an object that was never constructed.</li>
 * </ul>
 *
 * <p>The entry point class is loaded here rather than during discovery, for the same reason a language provider is: at
 * discovery time the jar is not reliably on the classpath yet, and loading it early would also skip whatever a coremod
 * was going to install.
 */
public class DeclarativeModContainer extends DummyModContainer {

    private final File source;
    private final String entrypointClass;
    @Nullable
    private final ClassLoader classLoader;
    private List<IEventListener> listeners = List.of();

    public DeclarativeModContainer(ModMetadata metadata, File source, String entrypointClass) {
        this(metadata, source, entrypointClass, null);
    }

    /**
     * @param classLoader the loader to resolve the entry point with, or {@code null} to ask the mod class loader at
     *                    registration time. Parameterised because the loader only exists once the game is starting, and
     *                    a class that can only be exercised through a launched game cannot be tested at all.
     */
    public DeclarativeModContainer(ModMetadata metadata, File source, String entrypointClass, @Nullable ClassLoader classLoader) {
        super(metadata);
        this.source = source;
        this.entrypointClass = entrypointClass;
        this.classLoader = classLoader;
    }

    @Override
    public File getSource() {
        return source;
    }

    /**
     * {@code true} because this container has no mod object <em>by design</em>.
     *
     * <p>Despite the name, the flag does not mean "wraps an instance": it is the signal
     * {@code LoadController.buildModObjectList} uses to tell an instance-less container apart from a mod whose
     * construction actually failed. Answering {@code false} would let every declarative mod load and then be reported as
     * "appears not to have constructed correctly".
     */
    @Override
    public boolean hasInstance() {
        return true;
    }

    @Override
    public boolean registerBus(EventBus bus, LoadController controller) {
        EntrypointRegistrar registrar = new EntrypointRegistrar(this);
        loadEntrypoint().register(registrar);
        // Lifecycle events are posted to this mod's own Guava channel, which dispatches through @Subscribe, so the
        // forwarding subscriber has to be a class we own - the entry point itself has nothing to annotate.
        if (registrar.hasPhaseHandlers()) {
            bus.register(new PhaseSubscriber(registrar));
        }
        this.listeners = List.copyOf(registrar.listeners());
        FMLLog.log.debug("Declarative mod {} registered from {}", getModId(), entrypointClass);
        return true;
    }

    /**
     * @return the gameplay listeners this container put on the shared bus, in registration order. Needed to take them off
     *         again when the mod is deactivated: those listener lists live on the event classes, not on the bus.
     */
    public List<IEventListener> listeners() {
        return listeners;
    }

    private IDeclarativeEntrypoint loadEntrypoint() {
        Class<?> type;
        try {
            ClassLoader loader = classLoader == null ? Loader.instance().getModClassLoader() : classLoader;
            type = Class.forName(entrypointClass, true, loader);
        } catch (ClassNotFoundException | LinkageError e) {
            throw new LoaderException("Mod '" + getModId() + "' (" + source.getName() + ") declares the entry point "
                    + entrypointClass + ", which cannot be loaded. Check the class name and that the class is in that jar.", e);
        }
        if (!IDeclarativeEntrypoint.class.isAssignableFrom(type)) {
            // Checked before instantiation so the failure names both sides instead of surfacing as a cast error.
            throw new LoaderException("The entry point " + entrypointClass + " declared by mod '" + getModId() + "' ("
                    + source.getName() + ") does not implement " + IDeclarativeEntrypoint.class.getName() + ".");
        }
        try {
            return (IDeclarativeEntrypoint) type.getConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new LoaderException("The entry point " + entrypointClass + " declared by mod '" + getModId() + "' ("
                    + source.getName() + ") has no public no-argument constructor.", e);
        }
    }

    @Override
    public String getSortingRules() {
        return "";
    }

    @Override
    public String toString() {
        return "DeclarativeModContainer[" + getModId() + " from " + (source == null ? "?" : source.getName()) + "]";
    }

    /**
     * Bridges the per-mod Guava channel to the registrar.
     *
     * <p>Takes {@link FMLEvent} rather than each concrete phase so one method sees them all: Guava resolves a post
     * against the handler's parameter type through the event's superclasses, so declaring the base type is enough.
     */
    private static final class PhaseSubscriber {

        private final EntrypointRegistrar registrar;

        PhaseSubscriber(EntrypointRegistrar registrar) {
            this.registrar = registrar;
        }

        @Subscribe
        public void onPhase(FMLEvent event) {
            registrar.dispatchPhase(event);
        }
    }
}
