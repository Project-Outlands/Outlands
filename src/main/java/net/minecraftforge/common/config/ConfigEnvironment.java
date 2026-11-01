package net.minecraftforge.common.config;

import java.io.File;

import javax.annotation.Nullable;

import net.minecraftforge.fml.relauncher.Side;

/**
 * Everything the config subsystem needs from the running game.
 *
 * <p>Before this existed, five call sites reached into {@link net.minecraftforge.fml.common.Loader} directly, which meant
 * no config code could run without a launched game - not a unit test, not a tool, not a migration script. The pieces
 * those five sites needed are small enough to name: a directory, a class loader, whether this is still the loading pass,
 * and a side.
 *
 * <p>{@link #loading()} replaces the {@code !Loader.hasReachedState(AVAILABLE)} check. That check was a time comparison
 * dressed up as a question about data flow: before {@code AVAILABLE} the file wins over the field, after it the field
 * wins. Naming the phase is what lets a caller outside the game answer it too.
 */
public interface ConfigEnvironment {

    /** The {@code config/} directory itself - the parent of every instance-level config file. */
    File configDir();

    /**
     * The per-user directory {@link Config.Storage#USER} configs live under, shared by every instance running as the
     * same user.
     *
     * @return the directory, or {@code null} when this platform offers no answer (no {@code user.home}, no
     *         {@code %APPDATA%}). Callers fall back to {@link #configDir()}, so an unresolvable machine still runs.
     */
    @Nullable
    File userConfigDir();

    /** The class loader mod {@code @Config} classes are loaded with. */
    ClassLoader modClassLoader();

    /**
     * @return {@code true} while the annotation model is still being bound to the file, i.e. while the file wins over
     *         whatever the field holds
     */
    boolean loading();

    /** The side this process is on, or {@code null} when it cannot be determined yet. */
    @Nullable
    Side side();
}
