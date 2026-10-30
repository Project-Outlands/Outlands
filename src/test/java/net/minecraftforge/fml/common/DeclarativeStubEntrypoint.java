package net.minecraftforge.fml.common;

import java.util.ArrayList;
import java.util.List;

import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLEvent;

/**
 * An entry point used only by tests: it records what it received, so a test can tell "the listener ran" from
 * "registration was configured".
 *
 * <p>Top level so {@code Class.forName} finds it the same way it finds a real one.
 */
public class DeclarativeStubEntrypoint implements IDeclarativeEntrypoint {

    /** Events delivered to the phase handler, in order. */
    public static final List<String> PHASES = new ArrayList<>();
    /** Events delivered to the gameplay handler, in order. */
    public static final List<String> GAMEPLAY = new ArrayList<>();

    public static void reset() {
        PHASES.clear();
        GAMEPLAY.clear();
    }

    @Override
    public void register(EntrypointRegistrar registrar) {
        registrar.onPhase(FMLEvent.class, event -> PHASES.add(event.getClass().getSimpleName()));
        registrar.onPhase(FMLPreInitializationEvent.class, event -> PHASES.add("special:" + event.getClass().getSimpleName()));
    }
}
