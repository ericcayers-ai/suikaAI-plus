package dev.suika.ai;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the package-private MctsNode via reflection so the MinMax norm
 *  and seeded visit preference stay covered without widening visibility. */
class MctsNodeTest {

    @Test
    void minMaxNormalisesToUnitInterval() throws Exception {
        Class<?> minMaxClz = Class.forName("dev.suika.ai.MctsNode$MinMax");
        var ctor = minMaxClz.getDeclaredConstructor();
        ctor.setAccessible(true);
        Object stats = ctor.newInstance();
        var update = minMaxClz.getDeclaredMethod("update", double.class);
        var norm = minMaxClz.getDeclaredMethod("norm", double.class);
        update.invoke(stats, -10.0);
        update.invoke(stats, 30.0);
        assertEquals(0.0, (double) norm.invoke(stats, -10.0), 1e-9);
        assertEquals(1.0, (double) norm.invoke(stats, 30.0), 1e-9);
        assertEquals(0.5, (double) norm.invoke(stats, 10.0), 1e-9);
    }

    @Test
    void minMaxBeforeSpreadReturnsHalf() throws Exception {
        Class<?> minMaxClz = Class.forName("dev.suika.ai.MctsNode$MinMax");
        var ctor = minMaxClz.getDeclaredConstructor();
        ctor.setAccessible(true);
        Object stats = ctor.newInstance();
        var norm = minMaxClz.getDeclaredMethod("norm", double.class);
        assertEquals(0.5, (double) norm.invoke(stats, 0.0), 1e-9);
    }

    @Test
    @SuppressWarnings("unchecked")
    void bestActionPrefersMostVisited() throws Exception {
        Class<?> nodeClz = Class.forName("dev.suika.ai.MctsNode");
        Constructor<?> ctor = nodeClz.getDeclaredConstructor(int.class, nodeClz);
        ctor.setAccessible(true);
        Object root = ctor.newInstance(-1, null);
        var expand = nodeClz.getDeclaredMethod("expand", List.class);
        expand.setAccessible(true);
        expand.invoke(root, List.of(0, 1, 2));
        var children = (List<Object>) nodeClz.getDeclaredMethod("children").invoke(root);
        var backup = nodeClz.getDeclaredMethod("backup", double.class);
        backup.setAccessible(true);
        // Seed column 1 with more visits (and equal value) so bestAction picks it.
        backup.invoke(children.get(0), 1.0);
        backup.invoke(children.get(1), 1.0);
        backup.invoke(children.get(1), 1.0);
        backup.invoke(children.get(2), 1.0);
        var best = nodeClz.getDeclaredMethod("bestAction");
        best.setAccessible(true);
        assertEquals(1, (int) best.invoke(root));
    }
}
