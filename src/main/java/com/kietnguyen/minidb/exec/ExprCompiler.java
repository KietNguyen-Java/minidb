package com.kietnguyen.minidb.exec;

import com.kietnguyen.minidb.SqlException;
import com.kietnguyen.minidb.sql.Ast;
import com.kietnguyen.minidb.sql.Ast.Binary;
import com.kietnguyen.minidb.sql.Ast.ColumnRef;
import com.kietnguyen.minidb.sql.Ast.Expr;
import com.kietnguyen.minidb.sql.Ast.IsNull;
import com.kietnguyen.minidb.sql.Ast.Literal;
import com.kietnguyen.minidb.sql.Ast.Op;
import com.kietnguyen.minidb.sql.Ast.Unary;
import com.kietnguyen.minidb.value.Value;

import java.util.List;

/**
 * Turns an expression into a function over a row. Column names are resolved to positions once,
 * at compile time, instead of once per row.
 *
 * <p>Comparisons follow SQL three-valued logic: anything compared with NULL is NULL (unknown),
 * and WHERE keeps a row only when its condition is true.
 */
public final class ExprCompiler {

    /** A compiled expression. */
    @FunctionalInterface
    public interface BoundExpr {
        Value eval(Value[] row);
    }

    /** A column of an intermediate result: the table (or alias) it came from and its name. */
    public record ColumnName(String qualifier, String name) {
        boolean matches(ColumnRef ref) {
            return name.equalsIgnoreCase(ref.name())
                    && (ref.qualifier() == null || ref.qualifier().equalsIgnoreCase(qualifier));
        }
    }

    private ExprCompiler() {
    }

    /** Position of the referenced column, failing if it is missing or ambiguous. */
    public static int resolve(ColumnRef ref, List<ColumnName> columns) {
        int found = -1;
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).matches(ref)) {
                if (found >= 0) {
                    throw new SqlException("Column " + ref.name() + " is ambiguous; write table.column");
                }
                found = i;
            }
        }
        if (found < 0) {
            throw new SqlException("Unknown column " + Ast.format(ref));
        }
        return found;
    }

    /** Like {@link #resolve} but returns -1 instead of failing. */
    public static int tryResolve(ColumnRef ref, List<ColumnName> columns) {
        try {
            return resolve(ref, columns);
        } catch (SqlException e) {
            return -1;
        }
    }

    public static BoundExpr compile(Expr expr, List<ColumnName> columns) {
        return switch (expr) {
            case Literal l -> {
                Value v = l.value();
                yield row -> v;
            }
            case ColumnRef c -> {
                int i = resolve(c, columns);
                yield row -> row[i];
            }
            case IsNull n -> {
                BoundExpr operand = compile(n.operand(), columns);
                boolean negated = n.negated();
                yield row -> Value.bool(operand.eval(row).isNull() != negated);
            }
            case Unary u -> {
                BoundExpr operand = compile(u.operand(), columns);
                yield switch (u.op()) {
                    case NOT -> row -> {
                        Boolean b = truth(operand.eval(row));
                        return b == null ? Value.NULL : Value.bool(!b);
                    };
                    case NEGATE -> row -> {
                        Value v = operand.eval(row);
                        if (v.isNull()) {
                            return Value.NULL;
                        }
                        return Value.of(-requireInt(v, "-"));
                    };
                };
            }
            case Binary b -> compileBinary(b, columns);
        };
    }

    private static BoundExpr compileBinary(Binary b, List<ColumnName> columns) {
        BoundExpr left = compile(b.left(), columns);
        BoundExpr right = compile(b.right(), columns);
        Op op = b.op();
        return switch (op) {
            case AND -> row -> {
                Boolean l = truth(left.eval(row));
                if (Boolean.FALSE.equals(l)) {
                    return Value.FALSE; // short-circuit: FALSE AND anything is FALSE
                }
                Boolean r = truth(right.eval(row));
                if (Boolean.FALSE.equals(r)) {
                    return Value.FALSE;
                }
                return l == null || r == null ? Value.NULL : Value.TRUE;
            };
            case OR -> row -> {
                Boolean l = truth(left.eval(row));
                if (Boolean.TRUE.equals(l)) {
                    return Value.TRUE;
                }
                Boolean r = truth(right.eval(row));
                if (Boolean.TRUE.equals(r)) {
                    return Value.TRUE;
                }
                return l == null || r == null ? Value.NULL : Value.FALSE;
            };
            case EQ, NE, LT, LE, GT, GE -> row -> compare(op, left.eval(row), right.eval(row));
            case ADD, SUB, MUL, DIV -> row -> arithmetic(op, left.eval(row), right.eval(row));
        };
    }

    static Value compare(Op op, Value a, Value b) {
        if (a.isNull() || b.isNull()) {
            return Value.NULL;
        }
        int c = a.compareTo(b);
        return Value.bool(switch (op) {
            case EQ -> c == 0;
            case NE -> c != 0;
            case LT -> c < 0;
            case LE -> c <= 0;
            case GT -> c > 0;
            case GE -> c >= 0;
            default -> throw new IllegalArgumentException(op.name());
        });
    }

    private static Value arithmetic(Op op, Value a, Value b) {
        if (a.isNull() || b.isNull()) {
            return Value.NULL;
        }
        long x = requireInt(a, op.symbol);
        long y = requireInt(b, op.symbol);
        try {
            return Value.of(switch (op) {
                case ADD -> Math.addExact(x, y);
                case SUB -> Math.subtractExact(x, y);
                case MUL -> Math.multiplyExact(x, y);
                case DIV -> {
                    if (y == 0) {
                        throw new SqlException("Division by zero");
                    }
                    yield x / y;
                }
                default -> throw new IllegalArgumentException(op.name());
            });
        } catch (ArithmeticException e) {
            throw new SqlException("Integer overflow in " + x + " " + op.symbol + " " + y);
        }
    }

    private static long requireInt(Value v, String operator) {
        if (v instanceof Value.IntValue i) {
            return i.value();
        }
        throw new SqlException("Operator " + operator + " needs INT values but got " + v.toSql());
    }

    /** TRUE, FALSE, or {@code null} for unknown. */
    public static Boolean truth(Value v) {
        return switch (v) {
            case Value.NullValue n -> null;
            case Value.IntValue i -> i.value() != 0;
            case Value.TextValue t -> throw new SqlException("Expected a condition but got text " + t.toSql());
        };
    }

    public static boolean isTrue(Value v) {
        return Boolean.TRUE.equals(truth(v));
    }
}
