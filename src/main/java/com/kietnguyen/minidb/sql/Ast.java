package com.kietnguyen.minidb.sql;

import com.kietnguyen.minidb.value.DataType;
import com.kietnguyen.minidb.value.Value;

import java.util.List;

/** The syntax tree produced by {@link Parser}. */
public final class Ast {

    private Ast() {
    }

    // ---------------------------------------------------------------- statements

    public sealed interface Statement
            permits CreateTable, CreateIndex, DropTable, Insert, Select, Update, Delete, Explain, Vacuum, ShowTables {
    }

    public record ColumnDef(String name, DataType type, boolean primaryKey, boolean notNull) {
    }

    public record CreateTable(String name, List<ColumnDef> columns) implements Statement {
    }

    public record CreateIndex(String name, String table, String column, boolean unique) implements Statement {
    }

    public record DropTable(String name) implements Statement {
    }

    /** {@code columns} is {@code null} when the statement lists no column names. */
    public record Insert(String table, List<String> columns, List<List<Expr>> rows) implements Statement {
    }

    public record TableRef(String name, String alias) {
        /** The name other clauses use to refer to this table. */
        public String qualifier() {
            return alias != null ? alias : name;
        }
    }

    public record Join(TableRef table, Expr on) {
    }

    /** An item of the SELECT list. {@code expr == null} stands for {@code *}. */
    public record SelectItem(Expr expr, String alias) {
        public boolean isStar() {
            return expr == null;
        }
    }

    public record OrderItem(Expr expr, boolean descending) {
    }

    public record Select(List<SelectItem> items, boolean countStar, TableRef from, Join join, Expr where,
                         List<OrderItem> orderBy, Long limit) implements Statement {
    }

    public record Assignment(String column, Expr value) {
    }

    public record Update(String table, List<Assignment> assignments, Expr where) implements Statement {
    }

    public record Delete(String table, Expr where) implements Statement {
    }

    public record Explain(Select select) implements Statement {
    }

    public record Vacuum() implements Statement {
    }

    public record ShowTables() implements Statement {
    }

    // ---------------------------------------------------------------- expressions

    public sealed interface Expr permits ColumnRef, Literal, Binary, Unary, IsNull {
    }

    public record ColumnRef(String qualifier, String name) implements Expr {
    }

    public record Literal(Value value) implements Expr {
    }

    public enum Op {
        EQ("="), NE("!="), LT("<"), LE("<="), GT(">"), GE(">="),
        AND("AND"), OR("OR"),
        ADD("+"), SUB("-"), MUL("*"), DIV("/");

        public final String symbol;

        Op(String symbol) {
            this.symbol = symbol;
        }

        public boolean isComparison() {
            return ordinal() <= GE.ordinal();
        }

        /** The operator that gives the same result with the operands swapped: {@code a < b} is {@code b > a}. */
        public Op flip() {
            return switch (this) {
                case LT -> GT;
                case LE -> GE;
                case GT -> LT;
                case GE -> LE;
                default -> this;
            };
        }
    }

    public record Binary(Op op, Expr left, Expr right) implements Expr {
    }

    public enum UnaryOp { NOT, NEGATE }

    public record Unary(UnaryOp op, Expr operand) implements Expr {
    }

    public record IsNull(Expr operand, boolean negated) implements Expr {
    }

    /** SQL text for an expression, used by EXPLAIN and for result column names. */
    public static String format(Expr expr) {
        return switch (expr) {
            case ColumnRef c -> c.qualifier() == null ? c.name() : c.qualifier() + "." + c.name();
            case Literal l -> l.value().toSql();
            case Binary b -> "(" + format(b.left()) + " " + b.op().symbol + " " + format(b.right()) + ")";
            case Unary u -> u.op() == UnaryOp.NOT ? "NOT " + format(u.operand()) : "-" + format(u.operand());
            case IsNull n -> format(n.operand()) + (n.negated() ? " IS NOT NULL" : " IS NULL");
        };
    }
}
