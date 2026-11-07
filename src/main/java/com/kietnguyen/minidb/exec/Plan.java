package com.kietnguyen.minidb.exec;

import com.kietnguyen.minidb.exec.ExprCompiler.BoundExpr;
import com.kietnguyen.minidb.exec.ExprCompiler.ColumnName;
import com.kietnguyen.minidb.sql.Ast;
import com.kietnguyen.minidb.sql.Ast.Expr;
import com.kietnguyen.minidb.sql.Ast.OrderItem;
import com.kietnguyen.minidb.storage.Index;
import com.kietnguyen.minidb.storage.Table;
import com.kietnguyen.minidb.value.Value;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Physical query plan operators. Each operator produces its full result as a list
 * (materialized execution), which keeps the code short; a production engine would stream rows.
 */
public final class Plan {

    private Plan() {
    }

    public interface Node {
        List<ColumnName> columns();

        List<Value[]> execute();

        /** One line for EXPLAIN. */
        String describe();

        default List<Node> children() {
            return List.of();
        }

        default void explain(StringBuilder out, int depth) {
            out.append("  ".repeat(depth)).append(depth == 0 ? "" : "-> ").append(describe()).append('\n');
            for (Node child : children()) {
                child.explain(out, depth + 1);
            }
        }
    }

    public static List<ColumnName> columnsOf(Table table) {
        return columnsOf(table, table.name());
    }

    public static List<ColumnName> columnsOf(Table table, String qualifier) {
        return table.columns().stream().map(c -> new ColumnName(qualifier, c.name())).toList();
    }

    static String tableLabel(Table table, String qualifier) {
        return qualifier.equalsIgnoreCase(table.name()) ? table.name() : table.name() + " AS " + qualifier;
    }

    static Value[] concat(Value[] a, Value[] b) {
        Value[] out = new Value[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    // ---------------------------------------------------------------- table access

    /** Reads rows of one table. UPDATE and DELETE use {@link #rowIds()} directly. */
    public abstract static class Scan implements Node {
        final Table table;
        final String qualifier;

        Scan(Table table, String qualifier) {
            this.table = table;
            this.qualifier = qualifier;
        }

        public abstract List<Long> rowIds();

        @Override
        public List<ColumnName> columns() {
            return columnsOf(table, qualifier);
        }

        @Override
        public List<Value[]> execute() {
            List<Value[]> out = new ArrayList<>();
            for (long id : rowIds()) {
                out.add(table.row(id));
            }
            return out;
        }
    }

    public static final class FullScan extends Scan {
        FullScan(Table table, String qualifier) {
            super(table, qualifier);
        }

        @Override
        public List<Long> rowIds() {
            return table.rowIds();
        }

        @Override
        public String describe() {
            return "FullScan " + tableLabel(table, qualifier) + " (" + table.rowCount() + " rows)";
        }
    }

    public static final class IndexLookup extends Scan {
        final Index index;
        final Value value;

        IndexLookup(Table table, String qualifier, Index index, Value value) {
            super(table, qualifier);
            this.index = index;
            this.value = value;
        }

        @Override
        public List<Long> rowIds() {
            return index.lookup(value);
        }

        @Override
        public String describe() {
            return "IndexLookup " + tableLabel(table, qualifier) + " using " + index.name()
                    + " (" + table.columns().get(index.column()).name() + " = " + value.toSql() + ")";
        }
    }

    public static final class IndexRange extends Scan {
        final Index index;
        final Value lo;
        final boolean loInclusive;
        final Value hi;
        final boolean hiInclusive;

        IndexRange(Table table, String qualifier, Index index, Value lo, boolean loInclusive, Value hi, boolean hiInclusive) {
            super(table, qualifier);
            this.index = index;
            this.lo = lo;
            this.loInclusive = loInclusive;
            this.hi = hi;
            this.hiInclusive = hiInclusive;
        }

        @Override
        public List<Long> rowIds() {
            return index.range(lo, loInclusive, hi, hiInclusive);
        }

        @Override
        public String describe() {
            String range = (lo == null ? "(-inf" : (loInclusive ? "[" : "(") + lo.toSql()) + ", "
                    + (hi == null ? "+inf)" : hi.toSql() + (hiInclusive ? "]" : ")"));
            return "IndexRange " + tableLabel(table, qualifier) + " using " + index.name()
                    + " (" + table.columns().get(index.column()).name() + " in " + range + ")";
        }
    }

    // ---------------------------------------------------------------- relational operators

    public static final class Filter implements Node {
        final Node child;
        final Expr condition;

        Filter(Node child, Expr condition) {
            this.child = child;
            this.condition = condition;
        }

        @Override
        public List<ColumnName> columns() {
            return child.columns();
        }

        @Override
        public List<Value[]> execute() {
            BoundExpr test = ExprCompiler.compile(condition, child.columns());
            return child.execute().stream().filter(row -> ExprCompiler.isTrue(test.eval(row))).toList();
        }

        @Override
        public String describe() {
            return "Filter " + Ast.format(condition);
        }

        @Override
        public List<Node> children() {
            return List.of(child);
        }
    }

    /** O(n * m): compares every pair. Used when the join condition is not a simple equality. */
    public static final class NestedLoopJoin implements Node {
        final Node left;
        final Node right;
        final Expr on;

        NestedLoopJoin(Node left, Node right, Expr on) {
            this.left = left;
            this.right = right;
            this.on = on;
        }

        @Override
        public List<ColumnName> columns() {
            return Stream.concat(left.columns().stream(), right.columns().stream()).toList();
        }

        @Override
        public List<Value[]> execute() {
            BoundExpr test = ExprCompiler.compile(on, columns());
            List<Value[]> rightRows = right.execute();
            List<Value[]> out = new ArrayList<>();
            for (Value[] l : left.execute()) {
                for (Value[] r : rightRows) {
                    Value[] row = concat(l, r);
                    if (ExprCompiler.isTrue(test.eval(row))) {
                        out.add(row);
                    }
                }
            }
            return out;
        }

        @Override
        public String describe() {
            return "NestedLoopJoin ON " + Ast.format(on);
        }

        @Override
        public List<Node> children() {
            return List.of(left, right);
        }
    }

    /** O(n + m): builds a hash table on the right input, then probes it with each left row. */
    public static final class HashJoin implements Node {
        final Node left;
        final Node right;
        final int leftKey;
        final int rightKey;
        final Expr on;

        HashJoin(Node left, Node right, int leftKey, int rightKey, Expr on) {
            this.left = left;
            this.right = right;
            this.leftKey = leftKey;
            this.rightKey = rightKey;
            this.on = on;
        }

        @Override
        public List<ColumnName> columns() {
            return Stream.concat(left.columns().stream(), right.columns().stream()).toList();
        }

        @Override
        public List<Value[]> execute() {
            Map<Value, List<Value[]>> buckets = new HashMap<>();
            for (Value[] r : right.execute()) {
                Value key = r[rightKey];
                if (!key.isNull()) { // NULL = NULL is not true, so NULL keys never join
                    buckets.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
                }
            }
            List<Value[]> out = new ArrayList<>();
            for (Value[] l : left.execute()) {
                for (Value[] r : buckets.getOrDefault(l[leftKey], List.of())) {
                    out.add(concat(l, r));
                }
            }
            return out;
        }

        @Override
        public String describe() {
            return "HashJoin ON " + Ast.format(on) + " (hash table built on right input)";
        }

        @Override
        public List<Node> children() {
            return List.of(left, right);
        }
    }

    /** O(n log m): for each left row, looks up matching right rows in an index. */
    public static final class IndexNestedLoopJoin implements Node {
        final Node left;
        final Table rightTable;
        final String rightQualifier;
        final Index index;
        final int leftKey;
        final Expr on;

        IndexNestedLoopJoin(Node left, Table rightTable, String rightQualifier, Index index, int leftKey, Expr on) {
            this.left = left;
            this.rightTable = rightTable;
            this.rightQualifier = rightQualifier;
            this.index = index;
            this.leftKey = leftKey;
            this.on = on;
        }

        @Override
        public List<ColumnName> columns() {
            return Stream.concat(left.columns().stream(), columnsOf(rightTable, rightQualifier).stream()).toList();
        }

        @Override
        public List<Value[]> execute() {
            List<Value[]> out = new ArrayList<>();
            for (Value[] l : left.execute()) {
                for (long id : index.lookup(l[leftKey])) {
                    out.add(concat(l, rightTable.row(id)));
                }
            }
            return out;
        }

        @Override
        public String describe() {
            return "IndexNestedLoopJoin ON " + Ast.format(on) + " (lookup " + tableLabel(rightTable, rightQualifier)
                    + " using " + index.name() + ")";
        }

        @Override
        public List<Node> children() {
            return List.of(left);
        }
    }

    public static final class Sort implements Node {
        final Node child;
        final List<OrderItem> order;

        Sort(Node child, List<OrderItem> order) {
            this.child = child;
            this.order = order;
        }

        @Override
        public List<ColumnName> columns() {
            return child.columns();
        }

        @Override
        public List<Value[]> execute() {
            Comparator<Value[]> cmp = null;
            for (OrderItem item : order) {
                BoundExpr key = ExprCompiler.compile(item.expr(), child.columns());
                Comparator<Value[]> c = Comparator.comparing(key::eval);
                if (item.descending()) {
                    c = c.reversed();
                }
                cmp = cmp == null ? c : cmp.thenComparing(c);
            }
            List<Value[]> rows = new ArrayList<>(child.execute());
            rows.sort(cmp); // List.sort is a stable merge sort (TimSort)
            return rows;
        }

        @Override
        public String describe() {
            return "Sort " + String.join(", ", order.stream()
                    .map(o -> Ast.format(o.expr()) + (o.descending() ? " DESC" : "")).toList());
        }

        @Override
        public List<Node> children() {
            return List.of(child);
        }
    }

    public static final class Limit implements Node {
        final Node child;
        final long limit;

        Limit(Node child, long limit) {
            this.child = child;
            this.limit = limit;
        }

        @Override
        public List<ColumnName> columns() {
            return child.columns();
        }

        @Override
        public List<Value[]> execute() {
            return child.execute().stream().limit(limit).toList();
        }

        @Override
        public String describe() {
            return "Limit " + limit;
        }

        @Override
        public List<Node> children() {
            return List.of(child);
        }
    }

    public static final class Project implements Node {
        final Node child;
        final List<Expr> exprs;
        final List<ColumnName> names;

        Project(Node child, List<Expr> exprs, List<ColumnName> names) {
            this.child = child;
            this.exprs = exprs;
            this.names = names;
        }

        @Override
        public List<ColumnName> columns() {
            return names;
        }

        @Override
        public List<Value[]> execute() {
            List<BoundExpr> bound = exprs.stream().map(e -> ExprCompiler.compile(e, child.columns())).toList();
            List<Value[]> out = new ArrayList<>();
            for (Value[] row : child.execute()) {
                Value[] projected = new Value[bound.size()];
                for (int i = 0; i < projected.length; i++) {
                    projected[i] = bound.get(i).eval(row);
                }
                out.add(projected);
            }
            return out;
        }

        @Override
        public String describe() {
            return "Project " + String.join(", ", names.stream().map(ColumnName::name).toList());
        }

        @Override
        public List<Node> children() {
            return List.of(child);
        }
    }

    public static final class CountStar implements Node {
        final Node child;

        CountStar(Node child) {
            this.child = child;
        }

        @Override
        public List<ColumnName> columns() {
            return List.of(new ColumnName(null, "COUNT(*)"));
        }

        @Override
        public List<Value[]> execute() {
            List<Value[]> out = new ArrayList<>();
            out.add(new Value[]{Value.of(child.execute().size())});
            return out;
        }

        @Override
        public String describe() {
            return "Count";
        }

        @Override
        public List<Node> children() {
            return List.of(child);
        }
    }
}
