package net.minecraftforge.fml.common.discovery;

import net.minecraftforge.fml.common.DummyModContainer;
import net.minecraftforge.fml.common.ModContainer;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class ASMDataTableTest {

    private static ModContainer containerWithSource(File source) {
        return new DummyModContainer() {
            @Override
            public File getSource() {
                return source;
            }
        };
    }

    @Test
    public void getAnnotationsForGroupsBySourceFile() {
        File jarA = new File("modA.jar");
        File jarB = new File("modB.jar");
        File jarC = new File("modC.jar");

        ModCandidate candidateA = new ModCandidate(jarA, jarA, ContainerType.JAR);
        ModCandidate candidateB = new ModCandidate(jarB, jarB, ContainerType.JAR);

        ASMDataTable table = new ASMDataTable();

        table.addASMData(candidateA, "AnnoX", "com/example/AClassOne", null, null);
        table.addASMData(candidateA, "AnnoY", "com/example/AClassTwo", null, null);
        // Same annotation type as above, but from a different mod's jar - must not leak across containers
        table.addASMData(candidateB, "AnnoX", "com/example/BClassOne", null, null);

        ModContainer containerA = containerWithSource(jarA);
        ModContainer containerB = containerWithSource(jarB);
        ModContainer containerC = containerWithSource(jarC); // no ASMData for this source

        table.addContainer(containerA);
        table.addContainer(containerB);
        table.addContainer(containerC);

        Set<ASMDataTable.ASMData> forA = table.getAnnotationsFor(containerA).get("AnnoX");
        assertEquals(1, forA.size());
        assertEquals("com/example/AClassOne", forA.iterator().next().getClassName());

        assertEquals(1, table.getAnnotationsFor(containerA).get("AnnoY").size());

        Set<ASMDataTable.ASMData> forB = table.getAnnotationsFor(containerB).get("AnnoX");
        assertEquals(1, forB.size());
        assertEquals("com/example/BClassOne", forB.iterator().next().getClassName());

        assertTrue(table.getAnnotationsFor(containerC).isEmpty());
    }

    @Test
    public void getAnnotationsForIsCachedAcrossCalls() {
        File jar = new File("mod.jar");
        ModCandidate candidate = new ModCandidate(jar, jar, ContainerType.JAR);
        ASMDataTable table = new ASMDataTable();
        table.addASMData(candidate, "AnnoX", "com/example/AClassOne", null, null);
        ModContainer container = containerWithSource(jar);
        table.addContainer(container);

        assertSame(table.getAnnotationsFor(container), table.getAnnotationsFor(container));
    }

    /**
     * Several mods can live in one jar, in which case they share one candidate and therefore one source file. The
     * attribution key is that file, not the container, so both mods see the very same annotations - callers that
     * need per-mod filtering have to narrow by modid themselves. This is the property the grouping rewrite was
     * written to preserve, and the one a future "exact attribution" refactor is most likely to break silently.
     */
    @Test
    public void containersSharingOneJarSeeTheSameAnnotations() {
        File jar = new File("multi.jar");
        ModCandidate candidate = new ModCandidate(jar, jar, ContainerType.JAR);
        ASMDataTable table = new ASMDataTable();
        table.addASMData(candidate, "AnnoX", "com/example/AClassOne", null, null);
        table.addASMData(candidate, "AnnoX", "com/example/AClassTwo", null, null);

        ModContainer first = containerWithSource(jar);
        ModContainer second = containerWithSource(jar);
        table.addContainer(first);
        table.addContainer(second);

        assertEquals(2, table.getAnnotationsFor(first).get("AnnoX").size());
        assertEquals(table.getAnnotationsFor(first), table.getAnnotationsFor(second));
    }
}
