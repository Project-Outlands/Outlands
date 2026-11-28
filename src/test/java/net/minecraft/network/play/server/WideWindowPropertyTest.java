package net.minecraft.network.play.server;

import io.netty.buffer.Unpooled;
import net.minecraft.network.PacketBuffer;
import net.minecraftforge.fml.common.registry.ForgeTestRunner;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;

@RunWith(ForgeTestRunner.class)
@ForgeTestRunner.Isolated
public class WideWindowPropertyTest
{
    @Test
    public void wideEnchantmentClueRoundTripsWithoutTruncation() throws Exception
    {
        assertRoundTrip(7, 4, 40000);
    }

    @Test
    public void ordinaryWindowPropertyRoundTrips() throws Exception
    {
        assertRoundTrip(7, 2, 300);
    }

    private static void assertRoundTrip(int windowId, int property, int value) throws Exception
    {
        SPacketWindowProperty original = new SPacketWindowProperty(windowId, property, value);
        PacketBuffer buffer = new PacketBuffer(Unpooled.buffer());
        try
        {
            original.writePacketData(buffer);
            SPacketWindowProperty decoded = new SPacketWindowProperty();
            decoded.readPacketData(buffer);

            assertEquals(windowId, decoded.getWindowId());
            assertEquals(property, decoded.getProperty());
            assertEquals(value, decoded.getValue());
            assertEquals(0, buffer.readableBytes());
        }
        finally
        {
            buffer.release();
        }
    }
}
