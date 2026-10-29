package net.minecraftforge.fml.common.discovery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

public class CorePluginBlacklistTest {

    @Test
    public void neidCorePluginIsIgnored(@TempDir Path temp) throws Exception {
        Path gameDir = Files.createDirectories(temp.resolve("game"));
        Path modsDir = Files.createDirectories(gameDir.resolve("mods"));
        writeNeidCoreMod(modsDir.resolve("neid.jar"));

        Path output = temp.resolve("neid-probe.log");
        Process process = startProbe(gameDir, output);
        try {
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                stop(process);
                fail("NEID probe timed out.\n" + readResult(output));
            }
            assertEquals(0, process.exitValue(), readResult(output));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    private static Process startProbe(Path gameDir, Path output) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable());
        command.addAll(ManagementFactory.getRuntimeMXBean().getInputArguments());
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(NeidProbe.class.getName());
        command.add(gameDir.toString());
        return new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(output.toFile())
                .start();
    }

    private static String javaExecutable() {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable).toString();
    }

    private static void stop(Process process) throws InterruptedException {
        process.destroy();
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
    }

    private static String readResult(Path output) throws IOException {
        return Files.readString(output, StandardCharsets.UTF_8);
    }

    private static void writeNeidCoreMod(Path file) throws IOException {
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.putValue("FMLCorePlugin", "ru.fewizz.neid.asm.Plugin");
        attributes.putValue("FMLCorePluginContainsFMLMod", "true");
        try (JarOutputStream ignored = new JarOutputStream(Files.newOutputStream(file), manifest)) {
        }
    }
}
