package net.minecraft.util;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The entity list a chunk keeps, walked the way the game walks it.
 *
 * <p><b>Why the walk is pinned.</b> {@code ClassInheritanceMultiMap} extends {@code AbstractSet}, so its inherited
 * {@code forEach} went through {@link #iterator()} - the set's iterator, an unmodifiable wrapper and the list's
 * iterator, three allocations per call - and a chunk's entity list is walked several times a frame by
 * {@code getEntitiesWithinAABB} and by the entity renderer. The override walks the backing list directly, and this is
 * the only thing about it that is worth a test: the order and the failure mode are the list's own either way.</p>
 */
class ClassInheritanceMultiMapTest {

    private static class Entity {
        private final int id;

        Entity(int id) {
            this.id = id;
        }

        @Override
        public String toString() {
            return "Entity" + id;
        }
    }

    @Test
    void forEachDoesNotGoThroughTheIterator() {
        // AbstractSet.forEach builds an iterator over the set; walking the backing list instead is what removes three
        // allocations per call, and making iterator() throw is the only way to observe that from outside.
        ClassInheritanceMultiMap<Entity> map = new ClassInheritanceMultiMap<Entity>(Entity.class) {
            @Override
            public java.util.Iterator<Entity> iterator() {
                throw new AssertionError("forEach must walk the list, not the set's iterator");
            }
        };
        map.add(new Entity(1));

        List<String> visited = new ArrayList<>();
        map.forEach(entity -> visited.add(entity.toString()));
        assertEquals(List.of("Entity1"), visited);
    }
}
