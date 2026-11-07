package com.kietnguyen.minidb.storage;

import com.kietnguyen.minidb.index.BPlusTree;
import com.kietnguyen.minidb.value.Value;

import java.util.List;
import java.util.Map;

/**
 * A secondary index on one column.
 *
 * <p>The tree key is {@code (value, rowId)}, which makes every key unique even when many rows share
 * a value. All rows with value {@code v} are the contiguous key range
 * {@code [(v, MIN), (v, MAX)]}, so equality lookups and range scans are both leaf-chain walks.
 */
public final class Index {

    /** Tree key: ordered by value first, then by row id. */
    public record Key(Value value, long rowId) implements Comparable<Key> {
        @Override
        public int compareTo(Key other) {
            int c = value.compareTo(other.value);
            return c != 0 ? c : Long.compare(rowId, other.rowId);
        }
    }

    private final String name;
    private final int column;
    private final boolean unique;
    private final BPlusTree<Key, Long> tree = new BPlusTree<>(64);

    public Index(String name, int column, boolean unique) {
        this.name = name;
        this.column = column;
        this.unique = unique;
    }

    public String name() {
        return name;
    }

    public int column() {
        return column;
    }

    public boolean unique() {
        return unique;
    }

    public int size() {
        return tree.size();
    }

    public void add(Value value, long rowId) {
        tree.put(new Key(value, rowId), rowId);
    }

    public void remove(Value value, long rowId) {
        tree.remove(new Key(value, rowId));
    }

    /** Row ids whose column equals {@code value}. NULL never equals anything. */
    public List<Long> lookup(Value value) {
        if (value.isNull()) {
            return List.of();
        }
        return ids(tree.range(new Key(value, Long.MIN_VALUE), true, new Key(value, Long.MAX_VALUE), true));
    }

    /** True if a row other than {@code rowId} already has {@code value}. */
    public boolean conflicts(Value value, long rowId) {
        return lookup(value).stream().anyMatch(id -> id != rowId);
    }

    /**
     * Row ids with {@code lo <= value <= hi}; a {@code null} bound is unbounded and either bound can be
     * exclusive. NULL values are never returned, because a comparison with NULL is never true.
     */
    public List<Long> range(Value lo, boolean loInclusive, Value hi, boolean hiInclusive) {
        Key from;
        boolean fromInclusive;
        if (lo == null) {
            from = new Key(Value.NULL, Long.MAX_VALUE); // skip past all NULLs, which sort first
            fromInclusive = false;
        } else {
            from = new Key(lo, loInclusive ? Long.MIN_VALUE : Long.MAX_VALUE);
            fromInclusive = loInclusive;
        }
        Key to = hi == null ? null : new Key(hi, hiInclusive ? Long.MAX_VALUE : Long.MIN_VALUE);
        return ids(tree.range(from, fromInclusive, to, hiInclusive));
    }

    private static List<Long> ids(List<Map.Entry<Key, Long>> entries) {
        return entries.stream().map(Map.Entry::getValue).toList();
    }
}
