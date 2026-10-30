package net.minecraftforge.fml.common;

import java.io.File;
import java.util.List;

import com.google.common.eventbus.EventBus;

import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers a mod that has no mod class: the container exists, the declared entry point becomes listeners, and the mod
 * receives lifecycle events through its own channel.
 *
 * <p>The parts worth pinning are the ones a reader would assume work without being told: the container reports the jar
 * as its source (the server side dereferences it), it registers itself as active (otherwise nothing is delivered at
 * all), and it still answers {@code false} to {@link ModContainer#matches} so nothing hands out an instance that was
 * never constructed.
 */
class DeclarativeModContainerTest {

    private static DeclarativeModContainer container(String entrypointClass) {
        ModMetadata metadata = new ModMetadata();
        metadata.modId = "declared";
        metadata.name = "Declared";
        metadata.version = "1.0";
        return new DeclarativeModContainer(metadata, new File("/mods/declared.jar"), entrypointClass,
                DeclarativeModContainerTest.class.getClassLoader());
    }

    @Test
    void theJarIsTheSourceSoTheServerSideCanReadIt() {
        // FMLServerHandler calls source.isDirectory() while scanning resource packs; null would be a null pointer rather
        // than "no resource pack".
        assertNotNull(container(DeclarativeStubEntrypoint.class.getName()).getSource());
    }

    @Test
    void thereIsNoModObjectAndNothingFindsOne() {
        DeclarativeModContainer container = container(DeclarativeStubEntrypoint.class.getName());

        assertEquals(null, container.getMod());
        assertFalse(container.matches(new Object()));

    }

    @Test
    void anInstanceLessModIsNotReportedAsAFailedConstruction() {
        // LoadController.buildModObjectList reports a severe error for containers that answer false here. A declarative
        // mod has no mod object on purpose, so answering the default (getMod() != null) would make every such mod load
        // and then be flagged as broken.
        assertTrue(container(DeclarativeStubEntrypoint.class.getName()).hasInstance());
    }

    @Test
    void registeringTheBusMakesTheModActive() {
        // Returning false would leave the mod out of the active list, which makes every listener below unreachable.
        assertTrue(container(DeclarativeStubEntrypoint.class.getName()).registerBus(new EventBus(), null));
    }

    @Test
    void lifecycleEventsReachTheEntrypointThroughItsOwnChannel() {
        DeclarativeStubEntrypoint.reset();
        DeclarativeModContainer container = container(DeclarativeStubEntrypoint.class.getName());
        EventBus channel = new EventBus();
        container.registerBus(channel, null);

        channel.post(new FMLPreInitializationEvent(null, null));

        // Both handlers fire: one declared for the base type, one for the concrete phase.
        assertTrue(DeclarativeStubEntrypoint.PHASES.contains("FMLPreInitializationEvent"),
                DeclarativeStubEntrypoint.PHASES.toString());
        assertTrue(DeclarativeStubEntrypoint.PHASES.contains("special:FMLPreInitializationEvent"),
                DeclarativeStubEntrypoint.PHASES.toString());
    }

    @Test
    void anEntrypointThatDoesNotImplementTheInterfaceFailsWithBothNames() {
        DeclarativeModContainer container = container(String.class.getName());

        LoaderException failure = assertThrows(LoaderException.class, () -> container.registerBus(new EventBus(), null));

        assertTrue(failure.getMessage().contains(String.class.getName()), failure.getMessage());
        assertTrue(failure.getMessage().contains("declared"), failure.getMessage());
        assertTrue(failure.getMessage().contains("IDeclarativeEntrypoint"), failure.getMessage());
    }

    @Test
    void aMissingEntrypointClassFailsAndNamesTheJar() {
        DeclarativeModContainer container = container("com.example.NotThere");

        LoaderException failure = assertThrows(LoaderException.class, () -> container.registerBus(new EventBus(), null));

        assertTrue(failure.getMessage().contains("com.example.NotThere"), failure.getMessage());
        assertTrue(failure.getMessage().contains("declared.jar"), failure.getMessage());
    }
}
