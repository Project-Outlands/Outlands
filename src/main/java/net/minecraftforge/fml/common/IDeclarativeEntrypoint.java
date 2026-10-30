package net.minecraftforge.fml.common;

/**
 * A mod entry point that is a listener supplier rather than a mod class.
 *
 * <p>The loader's lifecycle is built around containers, not around mod classes: it keys event channels by mod id, sorts
 * by declared dependencies and posts each phase's event to the bus a container registers. A jar written in a language
 * where "the mod" is not a class with a no-argument constructor therefore needs a container but not an instance - and
 * {@code getMod() == null} is already a supported state, not a workaround.
 *
 * <p>Implementations are named in the jar's {@code mcmod.info}:
 *
 * <pre>{@code
 * { "jar": { "schemaVersion": 1, "license": "EPL-2.0",
 *            "entrypoints": { "examplemod": "com.example.ExampleEntrypoint" } } }
 * }</pre>
 *
 * <p>Each declared namespace becomes one mod with that id, which is what makes dependency ordering, disabling and
 * per-mod event delivery work unchanged.
 *
 * <p>What an implementation cannot do, and should not pretend to:
 *
 * <ul>
 *   <li>receive {@code @Mod.EventHandler} callbacks - those are methods on an instance;</li>
 *   <li>be found through {@code @Mod.Instance} - nothing was constructed ({@link ModContainer#matches} answers
 *       false);</li>
 *   <li>use {@code @SidedProxy} - there are no fields to inject into. Side-specific behaviour belongs inside the
 *       implementation.</li>
 * </ul>
 *
 * <p>Cross-mod communication has to go through the event bus or an explicit service interface, for the same reason.
 */
public interface IDeclarativeEntrypoint {

    /**
     * Registers this entry point's listeners. Called once, when the mod's bus has been created.
     *
     * <p>Nothing here may assume the game is running yet: this happens during mod loading, before any phase event is
     * posted.
     */
    void register(EntrypointRegistrar registrar);
}
