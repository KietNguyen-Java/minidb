package com.kietnguyen.minidb.storage;

import com.kietnguyen.minidb.SqlException;
import com.kietnguyen.minidb.index.BPlusTree;
import com.kietnguyen.minidb.value.Value;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Rows are kept in a B+ tree keyed by an internal row id (a clustered index), so a full scan
 * returns rows in insertion order. Every table also keeps its secondary indexes up to date.
 */
public final class Table {

    private final String name;
    private final List<Column> columns;
    private final BPlusTree<Long, Value[]> rows = new BPlusTree<>(64);
    private final Map<String, Index> indexes = new LinkedHashMap<>();
    private long nextRowId = 1;

    public Table(String name, List<Column> columns) {
        this.name = name;
        this.columns = List.copyOf(columns);
    }

    public String name() {
        return name;
    }

    public List<Column> columns() {
        return columns;
    }

    public int rowCount() {
        return rows.size();
    }

    /** Position of the column, or -1. Column names are case-insensitive. */
    public int columnIndex(String column) {
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).name().equalsIgnoreCase(column)) {
                return i;
            }
        }
        return -1;
    }

    public int requireColumn(String column) {
        int i = columnIndex(column);
        if (i < 0) {
            throw new SqlException("Table " + name + " has no column " + column);
        }
        return i;
    }

    public Collection<Index> indexes() {
        return indexes.values();
    }

    public Index index(String indexName) {
        return indexes.get(indexName.toLowerCase());
    }

    /** The best index on a column: a unique one if there is one. */
    public Index indexOn(int column) {
        Index best = null;
        for (Index idx : indexes.values()) {
            if (idx.column() == column && (best == null || (idx.unique() && !best.unique()))) {
                best = idx;
            }
        }
        return best;
    }

    public long allocateRowId() {
        return nextRowId++;
    }

    public Value[] row(long rowId) {
        return rows.get(rowId);
    }

    public List<Long> rowIds() {
        return rows.keys();
    }

    public List<Map.Entry<Long, Value[]>> allRows() {
        return rows.entries();
    }

    // ---------------------------------------------------------------- changes

    public void addIndex(Index index) {
        if (indexes.containsKey(index.name().toLowerCase())) {
            throw new SqlException("Index " + index.name() + " already exists");
        }
        for (Map.Entry<Long, Value[]> e : rows.entries()) {
            Value v = e.getValue()[index.column()];
            if (index.unique() && !v.isNull() && index.conflicts(v, e.getKey())) {
                throw new SqlException("Cannot create unique index " + index.name()
                        + ": duplicate value " + v.toSql() + " in column " + columns.get(index.column()).name());
            }
            index.add(v, e.getKey());
        }
        indexes.put(index.name().toLowerCase(), index);
    }

    public void dropIndex(String indexName) {
        indexes.remove(indexName.toLowerCase());
    }

    public void insert(long rowId, Value[] values) {
        if (rows.containsKey(rowId)) {
            throw new IllegalStateException("row id " + rowId + " already used in " + name);
        }
        validate(values);
        checkUnique(values, rowId, null);
        rows.put(rowId, values.clone());
        for (Index idx : indexes.values()) {
            idx.add(values[idx.column()], rowId);
        }
        nextRowId = Math.max(nextRowId, rowId + 1);
    }

    public Value[] delete(long rowId) {
        Value[] old = rows.remove(rowId);
        if (old == null) {
            throw new IllegalStateException("no row " + rowId + " in " + name);
        }
        for (Index idx : indexes.values()) {
            idx.remove(old[idx.column()], rowId);
        }
        return old;
    }

    public Value[] update(long rowId, Value[] values) {
        Value[] old = rows.get(rowId);
        if (old == null) {
            throw new IllegalStateException("no row " + rowId + " in " + name);
        }
        validate(values);
        checkUnique(values, rowId, old);
        for (Index idx : indexes.values()) {
            int c = idx.column();
            if (!old[c].equals(values[c])) {
                idx.remove(old[c], rowId);
                idx.add(values[c], rowId);
            }
        }
        rows.put(rowId, values.clone());
        return old;
    }

    private void validate(Value[] values) {
        if (values.length != columns.size()) {
            throw new SqlException("Table " + name + " has " + columns.size() + " columns but "
                    + values.length + " values were given");
        }
        for (int i = 0; i < values.length; i++) {
            Column col = columns.get(i);
            Value v = values[i];
            if (v.isNull()) {
                if (col.notNull()) {
                    throw new SqlException("Column " + col.name() + " cannot be NULL");
                }
            } else if (v.type() != col.type()) {
                throw new SqlException("Column " + col.name() + " expects " + col.type()
                        + " but got " + v.type() + " " + v.toSql());
            }
        }
    }

    /** {@code old} is the row's current values for an update, or {@code null} for an insert. */
    private void checkUnique(Value[] values, long rowId, Value[] old) {
        for (Index idx : indexes.values()) {
            int c = idx.column();
            Value v = values[c];
            boolean changed = old == null || !old[c].equals(v);
            if (idx.unique() && changed && !v.isNull() && idx.conflicts(v, rowId)) {
                String what = columns.get(c).primaryKey() ? "primary key" : "unique index " + idx.name();
                throw new SqlException("Duplicate value " + v.toSql() + " for " + what
                        + " on " + name + "." + columns.get(c).name());
            }
        }
    }

    /** Copies all row data so a snapshot can be written while the table keeps changing. */
    public List<Map.Entry<Long, Value[]>> snapshot() {
        List<Map.Entry<Long, Value[]>> copy = new ArrayList<>();
        for (Map.Entry<Long, Value[]> e : rows.entries()) {
            copy.add(Map.entry(e.getKey(), e.getValue().clone()));
        }
        return copy;
    }
}
