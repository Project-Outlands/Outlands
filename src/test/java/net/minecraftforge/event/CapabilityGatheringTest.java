package net.minecraftforge.event;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.capabilities.CapabilityDispatcher;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.fml.common.eventhandler.EventBus;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gathering capabilities is an event every object posts, and a pack that attaches nothing gets nothing back from it.
 * The bus is asked before the event is built; these are the answers that have to stay identical.
 */
class CapabilityGatheringTest {

    private static final ICapabilityProvider PROVIDER = new ICapabilityProvider() {
        @Override
        public boolean hasCapability(net.minecraftforge.common.capabilities.Capability<?> capability,
                net.minecraft.util.EnumFacing facing) {
            return false;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T getCapability(net.minecraftforge.common.capabilities.Capability<T> capability,
                net.minecraft.util.EnumFacing facing) {
            return null;
        }
    };

    @Test
    void theBusReportsWhetherAnythingListens() {
        final EventBus bus = new EventBus();
        assertFalse(bus.hasListeners(AttachCapabilitiesEvent.class), "a fresh bus has nobody on it");

        final Object listener = new Object() {
            @SubscribeEvent
            public void onAttach(AttachCapabilitiesEvent<TileEntity> event) {
            }
        };
        bus.register(listener);
        assertTrue(bus.hasListeners(AttachCapabilitiesEvent.class));
        assertFalse(bus.hasListeners(net.minecraftforge.fml.common.eventhandler.impl.ExampleEvent.class),
                "and it answers per event type, not per bus");

        bus.unregister(listener);
        assertFalse(bus.hasListeners(AttachCapabilitiesEvent.class), "unregistering takes the answer back");
    }

    @Test
    void anUnwatchedGatherBuildsNoDispatcher() {
        if (MinecraftForge.EVENT_BUS.hasListeners(AttachCapabilitiesEvent.class)) {
            return; // another test in this JVM listens for it; nothing to prove here
        }
        assertNull(ForgeEventFactory.gatherCapabilities(new BareTileEntity()),
                "no dispatcher, and no event was built to find that out");
    }

    @Test
    void aListenerStillSeesTheEventAndItsCapabilityLands() {
        final AtomicInteger seen = new AtomicInteger();
        final Object listener = new Object() {
            @SubscribeEvent
            public void onAttach(AttachCapabilitiesEvent<TileEntity> event) {
                seen.incrementAndGet();
                event.addCapability(new ResourceLocation("test", "cap"), PROVIDER);
            }
        };
        // a tile entity gathers for itself in its constructor, so that post is not the one under test here
        final BareTileEntity tile = new BareTileEntity();
        MinecraftForge.EVENT_BUS.register(listener);
        try {
            seen.set(0);
            final CapabilityDispatcher dispatcher = ForgeEventFactory.gatherCapabilities(tile);
            assertEquals(1, seen.get(), "the event is posted when someone listens");
            assertNotNull(dispatcher, "and a listener that attaches something gets a dispatcher");
        } finally {
            MinecraftForge.EVENT_BUS.unregister(listener);
        }

        assertNull(ForgeEventFactory.gatherCapabilities(new BareTileEntity()),
                "and with the listener gone the fast path is back");
    }

    /** A tile entity needs no world to have capabilities gathered for it. */
    private static final class BareTileEntity extends TileEntity {
    }
}
