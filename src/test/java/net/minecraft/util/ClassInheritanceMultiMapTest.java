package net.minecraft.util;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The entity list a chunk keeps, walked the way the game walks it.
 *
 * <p><b>Why the walk is pinned.</b> {@code ClassInheritanceMultiMap} extends {@code AbstractSet}, so its inherited
 * {@code forEach} went through {@link #iterator()} - the set's iterator, an unmodifiable wrapper and the list's
 * iterator, three allocations per call - and a chunk's entity list is walked several times a frame by
 * {@code getEntitiesWithinAABB} and by the entity renderer. The override walks the backing list directly, which is
 * only safe if it visits the same elements in the same order and fails the same way when the list changes underneath
 * it.</p>
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

    private static final class Living extends Entity {
        Living(int id) {
            super(id);
        }
    }

    @Test
    void forEachVisitsEveryElementInInsertionOrder() {
        ClassInheritanceMultiMap<Entity> map = new ClassInheritanceMultiMap<>(Entity.class);
        map.add(new Entity(1));
        map.add(new Living(2));
        map.add(new Entity(3));

        List<String> visited = new ArrayList<>();
        map.forEach(entity -> visited.add(entity.toString()));

        assertEquals(List.of("Entity1", "Entity2", "Entity3"), visited,
                "the order the set's iterator produced, which is the list's order");
    }

    @Test
    void forEachFailsOnModificationLikeTheIteratorDoes() {
        // Both paths end up in ArrayList's own iteration, which throws when the list is structurally modified during
        // the walk. Pinned because a silent difference here would turn a loud crash into a wrong picture.
        ClassInheritanceMultiMap<Entity> map = new ClassInheritanceMultiMap<>(Entity.class);
        map.add(new Entity(1));
        map.add(new Entity(2));

        assertThrows(ConcurrentModificationException.class, () -> map.forEach(entity -> map.add(new Entity(9))));
    }

    @Test
    void forEachDoesNotGoThroughTheIterator() {
        // The point of the override. AbstractSet.forEach builds an iterator over the set; walking the backing list
        // instead is what removes three allocations per call, and this is the only way to observe that from outside.
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

    @Test
    void anEmptyMapVisitsNothing() {
        ClassInheritanceMultiMap<Entity> map = new ClassInheritanceMultiMap<>(Entity.class);
        List<Entity> visited = new ArrayList<>();
        map.forEach(visited::add);
        assertEquals(0, visited.size());
    }
}
