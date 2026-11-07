package com.kietnguyen.minidb;

import com.kietnguyen.minidb.index.BPlusTree;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BPlusTreeTest {

    @Test
    void insertsAndFindsKeys() {
        BPlusTree<Integer, String> tree = new BPlusTree<>(4);
        for (int i = 0; i < 100; i++) {
            tree.put(i, "v" + i);
        }
        assertEquals(100, tree.size());
        assertEquals("v42", tree.get(42));
        assertNull(tree.get(100));
        assertTrue(tree.height() > 1, "100 keys with order 4 must split the root");
        tree.checkInvariants();
    }

    @Test
    void putReplacesExistingValue() {
        BPlusTree<Integer, String> tree = new BPlusTree<>(4);
        assertNull(tree.put(1, "a"));
        assertEquals("a", tree.put(1, "b"));
        assertEquals("b", tree.get(1));
        assertEquals(1, tree.size());
    }

    @Test
    void rangeScanRespectsBounds() {
        BPlusTree<Integer, Integer> tree = new BPlusTree<>(3);
        for (int i = 0; i < 50; i += 2) {
            tree.put(i, i); // even numbers only
        }
        assertEquals(List.of(10, 12, 14), keys(tree.range(10, true, 14, true)));
        assertEquals(List.of(12), keys(tree.range(10, false, 14, false)));
        assertEquals(List.of(12, 14), keys(tree.range(11, true, 15, true)));
        assertEquals(List.of(0, 2), keys(tree.range(null, true, 3, true)));
        assertEquals(List.of(46, 48), keys(tree.range(45, true, null, true)));
        assertEquals(List.of(), keys(tree.range(100, true, null, true)));
    }

    @Test
    void removeShrinksTreeBackToOneLeaf() {
        BPlusTree<Integer, Integer> tree = new BPlusTree<>(3);
        for (int i = 0; i < 200; i++) {
            tree.put(i, i);
        }
        for (int i = 0; i < 200; i++) {
            assertEquals(i, tree.remove(i));
            tree.checkInvariants();
        }
        assertTrue(tree.isEmpty());
        assertEquals(1, tree.height());
        assertNull(tree.remove(5));
    }

    @Test
    void rejectsTooSmallOrder() {
        assertThrows(IllegalArgumentException.class, () -> new BPlusTree<Integer, Integer>(2));
    }

    /**
     * Property test: run thousands of random puts and removes against both the B+ tree and
     * java.util.TreeMap, and check they always agree and the tree's invariants always hold.
     */
    @ParameterizedTest
    @ValueSource(ints = {3, 4, 5, 8, 64})
    void behavesLikeTreeMapUnderRandomOperations(int order) {
        Random random = new Random(order * 7919L);
        BPlusTree<Integer, Integer> tree = new BPlusTree<>(order);
        TreeMap<Integer, Integer> model = new TreeMap<>();

        for (int step = 0; step < 20_000; step++) {
            int key = random.nextInt(2_000);
            if (random.nextInt(3) == 0) {
                assertEquals(model.remove(key), tree.remove(key));
            } else {
                assertEquals(model.put(key, step), tree.put(key, step));
            }
            if (step % 500 == 0) {
                tree.checkInvariants();
            }
        }
        tree.checkInvariants();
        assertEquals(model.size(), tree.size());
        assertEquals(new ArrayList<>(model.keySet()), tree.keys());

        for (int i = 0; i < 200; i++) {
            int a = random.nextInt(2_100);
            int b = random.nextInt(2_100);
            int lo = Math.min(a, b);
            int hi = Math.max(a, b);
            assertEquals(new ArrayList<>(model.subMap(lo, true, hi, false).keySet()),
                    keys(tree.range(lo, true, hi, false)));
        }
    }

    @Test
    void handlesShuffledInsertAndDeleteOrder() {
        List<Integer> keys = new ArrayList<>();
        for (int i = 0; i < 5_000; i++) {
            keys.add(i);
        }
        Collections.shuffle(keys, new Random(42));
        BPlusTree<Integer, Integer> tree = new BPlusTree<>(5);
        keys.forEach(k -> tree.put(k, k));
        tree.checkInvariants();

        Collections.shuffle(keys, new Random(7));
        for (int i = 0; i < keys.size() / 2; i++) {
            tree.remove(keys.get(i));
        }
        tree.checkInvariants();
        assertEquals(2_500, tree.size());
    }

    private static <K> List<K> keys(List<? extends Map.Entry<K, ?>> entries) {
        return entries.stream().map(Map.Entry::getKey).map(k -> (K) k).toList();
    }
}
