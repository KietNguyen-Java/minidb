package com.kietnguyen.minidb.exec;

import com.kietnguyen.minidb.SqlException;
import com.kietnguyen.minidb.exec.ExprCompiler.ColumnName;
import com.kietnguyen.minidb.sql.Ast;
import com.kietnguyen.minidb.sql.Ast.Binary;
import com.kietnguyen.minidb.sql.Ast.ColumnRef;
import com.kietnguyen.minidb.sql.Ast.Expr;
import com.kietnguyen.minidb.sql.Ast.Literal;
import com.kietnguyen.minidb.sql.Ast.Op;
import com.kietnguyen.minidb.sql.Ast.Select;
import com.kietnguyen.minidb.sql.Ast.SelectItem;
import com.kietnguyen.minidb.storage.Index;
import com.kietnguyen.minidb.storage.Table;
import com.kietnguyen.minidb.value.Value;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Chooses how to run a query. A rule-based planner:
 * <ol>
 *   <li>Access path: use an index when WHERE has {@code column op constant} on an indexed column.
 *       An equality on a unique index beats any other equality, which beats a range.</li>
 *   <li>Join: index nested-loop join if the inner table has an index on the join column,
 *       otherwise hash join for an equality, otherwise nested-loop join.</li>
 *   <li>The whole WHERE clause is still applied as a filter on top, so using an index never
 *       changes the result, only how many rows are read.</li>
 * </ol>
 */
public final class Planner {

    private final Function<String, Table> tables;

    public Planner(Function<String, Table> tables) {
        this.tables = tables;
    }

    public Plan.Node plan(Select select) {
        Table fromTable = tables.apply(select.from().name());
        String fromQualifier = select.from().qualifier();

        Table joinTable = select.join() == null ? null : tables.apply(select.join().table().name());
        String joinQualifier = select.join() == null ? null : select.join().table().qualifier();
        if (joinTable != null && joinQualifier.equalsIgnoreCase(fromQualifier)) {
            throw new SqlException("Both tables are called " + fromQualifier + "; give one an alias");
        }

        List<ColumnName> otherColumns = joinTable == null ? List.of() : Plan.columnsOf(joinTable, joinQualifier);
        Plan.Node node = accessPath(fromTable, fromQualifier, select.where(), otherColumns);

        if (joinTable != null) {
            node = join(node, joinTable, joinQualifier, select.join().on());
        }
        if (select.where() != null) {
            node = new Plan.Filter(node, select.where());
        }
        if (select.countStar()) {
            return new Plan.CountStar(node);
        }
        if (!select.orderBy().isEmpty()) {
            node = new Plan.Sort(node, select.orderBy());
        }
        if (select.limit() != null) {
            node = new Plan.Limit(node, select.limit());
        }
        return project(node, select.items());
    }

    /**
     * The cheapest way to read {@code table} given a WHERE clause. {@code otherColumns} are the
     * columns of a joined table; an unqualified name that also exists there is ambiguous and is
     * left to the filter to report.
     */
    public Plan.Scan accessPath(Table table, String qualifier, Expr where, List<ColumnName> otherColumns) {
        List<ColumnName> own = Plan.columnsOf(table, qualifier);
        Candidate best = null;
        for (Expr conjunct : conjuncts(where)) {
            Candidate c = candidate(table, own, otherColumns, conjunct);
            if (c != null && (best == null || c.score > best.score)) {
                best = c;
            }
        }
        if (best == null) {
            return new Plan.FullScan(table, qualifier);
        }
        if (best.op == Op.EQ) {
            return new Plan.IndexLookup(table, qualifier, best.index, best.value);
        }
        // Combine every range condition on the chosen column into one [lo, hi] interval.
        Value lo = null;
        Value hi = null;
        boolean loInclusive = true;
        boolean hiInclusive = true;
        for (Expr conjunct : conjuncts(where)) {
            Candidate c = candidate(table, own, otherColumns, conjunct);
            if (c == null || c.index != best.index) {
                continue;
            }
            switch (c.op) {
                case GT, GE -> {
                    int cmp = lo == null ? 1 : c.value.compareTo(lo);
                    if (cmp > 0 || (cmp == 0 && c.op == Op.GT)) {
                        lo = c.value;
                        loInclusive = c.op == Op.GE;
                    }
                }
                case LT, LE -> {
                    int cmp = hi == null ? -1 : c.value.compareTo(hi);
                    if (cmp < 0 || (cmp == 0 && c.op == Op.LT)) {
                        hi = c.value;
                        hiInclusive = c.op == Op.LE;
                    }
                }
                default -> {
                }
            }
        }
        return new Plan.IndexRange(table, qualifier, best.index, lo, loInclusive, hi, hiInclusive);
    }

    private record Candidate(Index index, Op op, Value value, int score) {
    }

    /** An index-usable condition {@code column op constant}, or {@code null}. */
    private Candidate candidate(Table table, List<ColumnName> own, List<ColumnName> other, Expr conjunct) {
        if (!(conjunct instanceof Binary b) || !b.op().isComparison() || b.op() == Op.NE) {
            return null;
        }
        ColumnRef column;
        Value constant;
        Op op;
        if (b.left() instanceof ColumnRef c && b.right() instanceof Literal l) {
            column = c;
            constant = l.value();
            op = b.op();
        } else if (b.right() instanceof ColumnRef c && b.left() instanceof Literal l) {
            column = c;
            constant = l.value();
            op = b.op().flip(); // 5 < age  is  age > 5
        } else {
            return null;
        }
        if (constant.isNull() || ExprCompiler.tryResolve(column, other) >= 0) {
            return null;
        }
        int pos = ExprCompiler.tryResolve(column, own);
        if (pos < 0) {
            return null;
        }
        Index index = table.indexOn(pos);
        if (index == null || constant.type() != table.columns().get(pos).type()) {
            return null;
        }
        int score = op == Op.EQ ? (index.unique() ? 3 : 2) : 1;
        return new Candidate(index, op, constant, score);
    }

    private Plan.Node join(Plan.Node left, Table right, String rightQualifier, Expr on) {
        List<ColumnName> rightColumns = Plan.columnsOf(right, rightQualifier);
        if (on instanceof Binary b && b.op() == Op.EQ
                && b.left() instanceof ColumnRef a && b.right() instanceof ColumnRef c) {
            int leftKey = ExprCompiler.tryResolve(a, left.columns());
            int rightKey = ExprCompiler.tryResolve(c, rightColumns);
            if (leftKey < 0 || rightKey < 0) { // written the other way round: ON o.user_id = u.id
                leftKey = ExprCompiler.tryResolve(c, left.columns());
                rightKey = ExprCompiler.tryResolve(a, rightColumns);
            }
            if (leftKey >= 0 && rightKey >= 0) {
                Index index = right.indexOn(rightKey);
                if (index != null) {
                    return new Plan.IndexNestedLoopJoin(left, right, rightQualifier, index, leftKey, on);
                }
                return new Plan.HashJoin(left, new Plan.FullScan(right, rightQualifier), leftKey, rightKey, on);
            }
        }
        return new Plan.NestedLoopJoin(left, new Plan.FullScan(right, rightQualifier), on);
    }

    private Plan.Node project(Plan.Node node, List<SelectItem> items) {
        List<Expr> exprs = new ArrayList<>();
        List<ColumnName> names = new ArrayList<>();
        for (SelectItem item : items) {
            if (item.isStar()) {
                for (ColumnName c : node.columns()) {
                    exprs.add(new ColumnRef(c.qualifier(), c.name()));
                    names.add(c);
                }
            } else {
                exprs.add(item.expr());
                String name = item.alias() != null ? item.alias()
                        : item.expr() instanceof ColumnRef c ? c.name() : Ast.format(item.expr());
                names.add(new ColumnName(null, name));
            }
        }
        // Compile once now so unknown columns fail before any row is read.
        exprs.forEach(e -> ExprCompiler.compile(e, node.columns()));
        return new Plan.Project(node, exprs, names);
    }

    /** Splits {@code a AND b AND c} into {@code [a, b, c]}. */
    public static List<Expr> conjuncts(Expr where) {
        List<Expr> out = new ArrayList<>();
        collect(where, out);
        return out;
    }

    private static void collect(Expr e, List<Expr> out) {
        if (e == null) {
            return;
        }
        if (e instanceof Binary b && b.op() == Op.AND) {
            collect(b.left(), out);
            collect(b.right(), out);
        } else {
            out.add(e);
        }
    }
}
