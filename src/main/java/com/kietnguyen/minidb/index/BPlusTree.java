package com.kietnguyen.minidb.index;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * An in-memory B+ tree with unique keys.
 *
 * <p>Values are stored only in leaves, and leaves are linked left to right, so a range scan
 * descends once to the first leaf and then walks the leaf chain. Every operation is
 * O(log n) in the number of keys; a range scan is O(log n + k) for k results.
 *
 * <p>Separator rule: in an internal node, {@code keys[i]} is the smallest key that can appear in
 * {@code children[i + 1]}. A search for key {@code k} therefore follows the first separator
 * that is strictly greater than {@code k}.
 */
public final class BPlusTree<K extends Comparable<? super K>, V> {

    private final int order;   // max children of an internal node
    private final int maxKeys; // order - 1, for leaves and internal nodes
    private final int minKeys; // ceil(order / 2) - 1, for every node except the root

    private Node<K, V> root = new Leaf<>();
    private int size;

    public BPlusTree(int order) {
        if (order < 3) {
            throw new IllegalArgumentException("order must be at least 3");
        }
        this.order = order;
        this.maxKeys = order - 1;
        this.minKeys = (order + 1) / 2 - 1;
    }

    public int size() {
        return size;
    }

    public int order() {
        return order;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    /** Number of levels, counting the leaf level. An empty tree has height 1. */
    public int height() {
        int h = 1;
        for (Node<K, V> n = root; n instanceof Internal<K, V> in; n = in.children.getFirst()) {
            h++;
        }
        return h;
    }

    public V get(K key) {
        Objects.requireNonNull(key);
        Leaf<K, V> leaf = findLeaf(key);
        int i = Collections.binarySearch(leaf.keys, key);
        return i >= 0 ? leaf.values.get(i) : null;
    }

    public boolean containsKey(K key) {
        return get(key) != null;
    }

    /** Inserts or replaces. Returns the previous value, or {@code null}. */
    public V put(K key, V value) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(value);
        List<V> previous = new ArrayList<>(1);
        Split<K, V> split = insert(root, key, value, previous);
        if (split != null) {
            Internal<K, V> newRoot = new Internal<>();
            newRoot.keys.add(split.separator());
            newRoot.children.add(root);
            newRoot.children.add(split.right());
            root = newRoot;
        }
        return previous.isEmpty() ? null : previous.getFirst();
    }

    /** Removes the key. Returns the removed value, or {@code null} if the key was absent. */
    public V remove(K key) {
        Objects.requireNonNull(key);
        List<V> removed = new ArrayList<>(1);
        delete(root, key, removed);
        if (root instanceof Internal<K, V> in && in.keys.isEmpty()) {
            root = in.children.getFirst(); // the tree shrinks by one level
        }
        return removed.isEmpty() ? null : removed.getFirst();
    }

    /**
     * Entries with {@code from <= key <= to} in key order. A {@code null} bound means unbounded,
     * and each bound can be made exclusive.
     */
    public List<Map.Entry<K, V>> range(K from, boolean fromInclusive, K to, boolean toInclusive) {
        List<Map.Entry<K, V>> out = new ArrayList<>();
        Leaf<K, V> leaf = from == null ? firstLeaf() : findLeaf(from);
        int i = 0;
        if (from != null) {
            int pos = Collections.binarySearch(leaf.keys, from);
            i = pos >= 0 ? (fromInclusive ? pos : pos + 1) : -pos - 1;
        }
        for (; leaf != null; leaf = leaf.next, i = 0) {
            for (; i < leaf.keys.size(); i++) {
                K key = leaf.keys.get(i);
                if (to != null) {
                    int c = key.compareTo(to);
                    if (c > 0 || (c == 0 && !toInclusive)) {
                        return out;
                    }
                }
                out.add(new AbstractMap.SimpleImmutableEntry<>(key, leaf.values.get(i)));
            }
        }
        return out;
    }

    public List<Map.Entry<K, V>> entries() {
        return range(null, true, null, true);
    }

    public List<K> keys() {
        List<K> out = new ArrayList<>(size);
        for (Leaf<K, V> leaf = firstLeaf(); leaf != null; leaf = leaf.next) {
            out.addAll(leaf.keys);
        }
        return out;
    }

    // ---------------------------------------------------------------- search

    private Leaf<K, V> findLeaf(K key) {
        Node<K, V> n = root;
        while (n instanceof Internal<K, V> in) {
            n = in.children.get(childIndex(in, key));
        }
        return (Leaf<K, V>) n;
    }

    private Leaf<K, V> firstLeaf() {
        Node<K, V> n = root;
        while (n instanceof Internal<K, V> in) {
            n = in.children.getFirst();
        }
        return (Leaf<K, V>) n;
    }

    /** Index of the child to follow: the number of separators that are {@code <= key}. */
    private int childIndex(Internal<K, V> node, K key) {
        int lo = 0;
        int hi = node.keys.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (node.keys.get(mid).compareTo(key) <= 0) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    // ---------------------------------------------------------------- insert

    private record Split<K, V>(K separator, Node<K, V> right) {
    }

    private Split<K, V> insert(Node<K, V> node, K key, V value, List<V> previous) {
        if (node instanceof Leaf<K, V> leaf) {
            int i = Collections.binarySearch(leaf.keys, key);
            if (i >= 0) {
                previous.add(leaf.values.set(i, value));
                return null;
            }
            int at = -i - 1;
            leaf.keys.add(at, key);
            leaf.values.add(at, value);
            size++;
            return leaf.keys.size() > maxKeys ? splitLeaf(leaf) : null;
        }
        Internal<K, V> in = (Internal<K, V>) node;
        int ci = childIndex(in, key);
        Split<K, V> split = insert(in.children.get(ci), key, value, previous);
        if (split == null) {
            return null;
        }
        in.keys.add(ci, split.separator());
        in.children.add(ci + 1, split.right());
        return in.keys.size() > maxKeys ? splitInternal(in) : null;
    }

    /** The right half moves to a new leaf; its first key is copied up as the separator. */
    private Split<K, V> splitLeaf(Leaf<K, V> leaf) {
        int mid = leaf.keys.size() / 2;
        Leaf<K, V> right = new Leaf<>();
        moveTail(leaf.keys, mid, right.keys);
        moveTail(leaf.values, mid, right.values);
        right.next = leaf.next;
        leaf.next = right;
        return new Split<>(right.keys.getFirst(), right);
    }

    /** The middle key moves up to the parent; it is not kept in either half. */
    private Split<K, V> splitInternal(Internal<K, V> node) {
        int mid = node.keys.size() / 2;
        K separator = node.keys.get(mid);
        Internal<K, V> right = new Internal<>();
        moveTail(node.keys, mid + 1, right.keys);
        node.keys.removeLast();
        moveTail(node.children, mid + 1, right.children);
        return new Split<>(separator, right);
    }

    private static <T> void moveTail(List<T> from, int start, List<T> to) {
        List<T> tail = from.subList(start, from.size());
        to.addAll(tail);
        tail.clear();
    }

    // ---------------------------------------------------------------- delete

    private boolean delete(Node<K, V> node, K key, List<V> removed) {
        if (node instanceof Leaf<K, V> leaf) {
            int i = Collections.binarySearch(leaf.keys, key);
            if (i < 0) {
                return false;
            }
            leaf.keys.remove(i);
            removed.add(leaf.values.remove(i));
            size--;
            return true;
        }
        Internal<K, V> in = (Internal<K, V>) node;
        int ci = childIndex(in, key);
        if (!delete(in.children.get(ci), key, removed)) {
            return false;
        }
        if (in.children.get(ci).keys.size() < minKeys) {
            rebalance(in, ci);
        }
        return true;
    }

    /** Fixes an underfull child: borrow from a sibling that can spare a key, otherwise merge. */
    private void rebalance(Internal<K, V> parent, int i) {
        Node<K, V> child = parent.children.get(i);
        Node<K, V> left = i > 0 ? parent.children.get(i - 1) : null;
        Node<K, V> right = i + 1 < parent.children.size() ? parent.children.get(i + 1) : null;

        if (left != null && left.keys.size() > minKeys) {
            borrowFromLeft(parent, i, left, child);
        } else if (right != null && right.keys.size() > minKeys) {
            borrowFromRight(parent, i, child, right);
        } else if (left != null) {
            merge(parent, i - 1, left, child);
        } else {
            merge(parent, i, child, right);
        }
    }

    private void borrowFromLeft(Internal<K, V> parent, int i, Node<K, V> left, Node<K, V> child) {
        if (child instanceof Leaf<K, V> c && left instanceof Leaf<K, V> l) {
            c.keys.addFirst(l.keys.removeLast());
            c.values.addFirst(l.values.removeLast());
            parent.keys.set(i - 1, c.keys.getFirst());
        } else {
            Internal<K, V> c = (Internal<K, V>) child;
            Internal<K, V> l = (Internal<K, V>) left;
            c.keys.addFirst(parent.keys.get(i - 1));
            c.children.addFirst(l.children.removeLast());
            parent.keys.set(i - 1, l.keys.removeLast());
        }
    }

    private void borrowFromRight(Internal<K, V> parent, int i, Node<K, V> child, Node<K, V> right) {
        if (child instanceof Leaf<K, V> c && right instanceof Leaf<K, V> r) {
            c.keys.addLast(r.keys.removeFirst());
            c.values.addLast(r.values.removeFirst());
            parent.keys.set(i, r.keys.getFirst());
        } else {
            Internal<K, V> c = (Internal<K, V>) child;
            Internal<K, V> r = (Internal<K, V>) right;
            c.keys.addLast(parent.keys.get(i));
            c.children.addLast(r.children.removeFirst());
            parent.keys.set(i, r.keys.removeFirst());
        }
    }

    /** Merges {@code children[leftIndex + 1]} into {@code children[leftIndex]}. */
    private void merge(Internal<K, V> parent, int leftIndex, Node<K, V> left, Node<K, V> right) {
        if (left instanceof Leaf<K, V> l && right instanceof Leaf<K, V> r) {
            l.keys.addAll(r.keys);
            l.values.addAll(r.values);
            l.next = r.next;
        } else {
            Internal<K, V> l = (Internal<K, V>) left;
            Internal<K, V> r = (Internal<K, V>) right;
            l.keys.add(parent.keys.get(leftIndex)); // the separator comes down
            l.keys.addAll(r.keys);
            l.children.addAll(r.children);
        }
        parent.keys.remove(leftIndex);
        parent.children.remove(leftIndex + 1);
    }

    // ---------------------------------------------------------------- invariants (for tests)

    /** Throws {@link IllegalStateException} if any B+ tree invariant is broken. */
    public void checkInvariants() {
        int[] leafDepth = {-1};
        check(root, null, null, 0, leafDepth, true);

        int count = 0;
        K previous = null;
        for (Leaf<K, V> leaf = firstLeaf(); leaf != null; leaf = leaf.next) {
            for (K key : leaf.keys) {
                if (previous != null && previous.compareTo(key) >= 0) {
                    throw new IllegalStateException("leaf chain out of order at " + key);
                }
                previous = key;
                count++;
            }
        }
        if (count != size) {
            throw new IllegalStateException("size is " + size + " but leaves hold " + count);
        }
    }

    private void check(Node<K, V> node, K lo, K hi, int depth, int[] leafDepth, boolean isRoot) {
        int n = node.keys.size();
        if (n > maxKeys) {
            throw new IllegalStateException("node has " + n + " keys, max is " + maxKeys);
        }
        if (!isRoot && n < minKeys) {
            throw new IllegalStateException("node has " + n + " keys, min is " + minKeys);
        }
        for (int i = 0; i < n; i++) {
            K key = node.keys.get(i);
            if (i > 0 && node.keys.get(i - 1).compareTo(key) >= 0) {
                throw new IllegalStateException("keys out of order in node");
            }
            if ((lo != null && key.compareTo(lo) < 0) || (hi != null && key.compareTo(hi) >= 0)) {
                throw new IllegalStateException("key " + key + " outside [" + lo + ", " + hi + ")");
            }
        }
        if (node instanceof Leaf<K, V> leaf) {
            if (leaf.values.size() != n) {
                throw new IllegalStateException("leaf has " + n + " keys but " + leaf.values.size() + " values");
            }
            if (leafDepth[0] == -1) {
                leafDepth[0] = depth;
            } else if (leafDepth[0] != depth) {
                throw new IllegalStateException("leaves at different depths");
            }
            return;
        }
        Internal<K, V> in = (Internal<K, V>) node;
        if (in.children.size() != n + 1) {
            throw new IllegalStateException("internal node has " + n + " keys but " + in.children.size() + " children");
        }
        for (int i = 0; i <= n; i++) {
            K childLo = i == 0 ? lo : in.keys.get(i - 1);
            K childHi = i == n ? hi : in.keys.get(i);
            check(in.children.get(i), childLo, childHi, depth + 1, leafDepth, false);
        }
    }

    // ---------------------------------------------------------------- nodes

    private abstract static sealed class Node<K, V> permits Leaf, Internal {
        final ArrayList<K> keys = new ArrayList<>();
    }

    private static final class Leaf<K, V> extends Node<K, V> {
        final ArrayList<V> values = new ArrayList<>();
        Leaf<K, V> next;
    }

    private static final class Internal<K, V> extends Node<K, V> {
        final ArrayList<Node<K, V>> children = new ArrayList<>();
    }
}
