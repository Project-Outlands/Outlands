package net.minecraftforge.fml.common.registry;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@ForgeTestRunner.Isolated
class ForgeTestRunnerIsolationTest
{
    @Test
    void junitFiveExtensionEnablesVanillaGameData()
    {
        assertEquals("false", System.getProperty("forge.disableVanillaGameData"));
    }
}
