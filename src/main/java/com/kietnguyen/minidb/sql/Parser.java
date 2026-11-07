package com.kietnguyen.minidb.sql;

import com.kietnguyen.minidb.SqlException;
import com.kietnguyen.minidb.sql.Ast.*;
import com.kietnguyen.minidb.sql.Lexer.Kind;
import com.kietnguyen.minidb.sql.Lexer.Token;
import com.kietnguyen.minidb.value.DataType;
import com.kietnguyen.minidb.value.Value;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Recursive-descent parser. Operator precedence, lowest first:
 * {@code OR}, {@code AND}, {@code NOT}, comparisons and {@code IS NULL}, {@code + -}, {@code * /}, unary minus.
 */
public final class Parser {

    /** Words that end a table reference, so they are never taken as an alias. */
    private static final Set<String> RESERVED = Set.of(
            "SELECT", "FROM", "WHERE", "JOIN", "INNER", "ON", "ORDER", "BY", "LIMIT", "AND", "OR", "NOT",
            "SET", "VALUES", "AS", "IS", "NULL", "ASC", "DESC");

    private final List<Token> tokens;
    private int pos;

    private Parser(String sql) {
        this.tokens = Lexer.tokenize(sql);
    }

    /** Parses one or more statements separated by semicolons. */
    public static List<Statement> parseAll(String sql) {
        Parser p = new Parser(sql);
        List<Statement> statements = new ArrayList<>();
        while (true) {
            while (p.acceptSymbol(";")) {
                // skip empty statements
            }
            if (p.peek().kind() == Kind.EOF) {
                return statements;
            }
            statements.add(p.statement());
            if (!p.acceptSymbol(";") && p.peek().kind() != Kind.EOF) {
                throw p.error("Expected ';' or end of input");
            }
        }
    }

    public static Statement parse(String sql) {
        List<Statement> all = parseAll(sql);
        if (all.size() != 1) {
            throw new SqlException("Expected exactly one statement, found " + all.size());
        }
        return all.getFirst();
    }

    // ---------------------------------------------------------------- statements

    private Statement statement() {
        Token t = peek();
        if (t.isKeyword("CREATE")) return create();
        if (t.isKeyword("DROP")) return drop();
        if (t.isKeyword("INSERT")) return insert();
        if (t.isKeyword("SELECT")) return select();
        if (t.isKeyword("UPDATE")) return update();
        if (t.isKeyword("DELETE")) return delete();
        if (t.isKeyword("EXPLAIN")) {
            next();
            if (!peek().isKeyword("SELECT")) {
                throw error("EXPLAIN supports SELECT statements only");
            }
            return new Explain(select());
        }
        if (t.isKeyword("VACUUM")) {
            next();
            return new Vacuum();
        }
        if (t.isKeyword("SHOW")) {
            next();
            expectKeyword("TABLES");
            return new ShowTables();
        }
        throw error("Expected a statement");
    }

    private Statement create() {
        expectKeyword("CREATE");
        boolean unique = acceptKeyword("UNIQUE");
        if (acceptKeyword("INDEX")) {
            String name = identifier();
            expectKeyword("ON");
            String table = identifier();
            expectSymbol("(");
            String column = identifier();
            expectSymbol(")");
            return new CreateIndex(name, table, column, unique);
        }
        if (unique) {
            throw error("Expected INDEX after UNIQUE");
        }
        expectKeyword("TABLE");
        String name = identifier();
        expectSymbol("(");
        List<ColumnDef> columns = new ArrayList<>();
        do {
            String column = identifier();
            DataType type = DataType.parse(identifier());
            boolean primaryKey = false;
            boolean notNull = false;
            while (true) {
                if (acceptKeyword("PRIMARY")) {
                    expectKeyword("KEY");
                    primaryKey = true;
                } else if (acceptKeyword("NOT")) {
                    expectKeyword("NULL");
                    notNull = true;
                } else {
                    break;
                }
            }
            columns.add(new ColumnDef(column, type, primaryKey, notNull || primaryKey));
        } while (acceptSymbol(","));
        expectSymbol(")");
        return new CreateTable(name, columns);
    }

    private Statement drop() {
        expectKeyword("DROP");
        expectKeyword("TABLE");
        return new DropTable(identifier());
    }

    private Statement insert() {
        expectKeyword("INSERT");
        expectKeyword("INTO");
        String table = identifier();
        List<String> columns = null;
        if (acceptSymbol("(")) {
            columns = new ArrayList<>();
            do {
                columns.add(identifier());
            } while (acceptSymbol(","));
            expectSymbol(")");
        }
        expectKeyword("VALUES");
        List<List<Expr>> rows = new ArrayList<>();
        do {
            expectSymbol("(");
            List<Expr> row = new ArrayList<>();
            do {
                row.add(expression());
            } while (acceptSymbol(","));
            expectSymbol(")");
            rows.add(row);
        } while (acceptSymbol(","));
        return new Insert(table, columns, rows);
    }

    private Select select() {
        expectKeyword("SELECT");
        List<SelectItem> items = new ArrayList<>();
        boolean countStar = false;
        if (peek().isKeyword("COUNT") && peek(1).isSymbol("(")) {
            next();
            expectSymbol("(");
            expectSymbol("*");
            expectSymbol(")");
            countStar = true;
        } else {
            do {
                if (acceptSymbol("*")) {
                    items.add(new SelectItem(null, null));
                } else {
                    Expr e = expression();
                    String alias = acceptKeyword("AS") ? identifier() : null;
                    items.add(new SelectItem(e, alias));
                }
            } while (acceptSymbol(","));
        }
        expectKeyword("FROM");
        TableRef from = tableRef();

        Join join = null;
        if (acceptKeyword("INNER")) {
            expectKeyword("JOIN");
            join = joinClause();
        } else if (acceptKeyword("JOIN")) {
            join = joinClause();
        }

        Expr where = acceptKeyword("WHERE") ? expression() : null;

        List<OrderItem> orderBy = new ArrayList<>();
        if (acceptKeyword("ORDER")) {
            expectKeyword("BY");
            do {
                Expr e = expression();
                boolean desc = acceptKeyword("DESC");
                if (!desc) {
                    acceptKeyword("ASC");
                }
                orderBy.add(new OrderItem(e, desc));
            } while (acceptSymbol(","));
        }

        Long limit = null;
        if (acceptKeyword("LIMIT")) {
            Token n = next();
            if (n.kind() != Kind.NUMBER) {
                throw error("Expected a number after LIMIT", n);
            }
            limit = Long.parseLong(n.text());
        }
        return new Select(items, countStar, from, join, where, orderBy, limit);
    }

    private Join joinClause() {
        TableRef table = tableRef();
        expectKeyword("ON");
        return new Join(table, expression());
    }

    private TableRef tableRef() {
        String name = identifier();
        String alias = null;
        if (acceptKeyword("AS")) {
            alias = identifier();
        } else if (peek().kind() == Kind.IDENT && !RESERVED.contains(peek().text().toUpperCase())) {
            alias = identifier();
        }
        return new TableRef(name, alias);
    }

    private Statement update() {
        expectKeyword("UPDATE");
        String table = identifier();
        expectKeyword("SET");
        List<Assignment> assignments = new ArrayList<>();
        do {
            String column = identifier();
            expectSymbol("=");
            assignments.add(new Assignment(column, expression()));
        } while (acceptSymbol(","));
        Expr where = acceptKeyword("WHERE") ? expression() : null;
        return new Update(table, assignments, where);
    }

    private Statement delete() {
        expectKeyword("DELETE");
        expectKeyword("FROM");
        String table = identifier();
        Expr where = acceptKeyword("WHERE") ? expression() : null;
        return new Delete(table, where);
    }

    // ---------------------------------------------------------------- expressions

    private Expr expression() {
        return or();
    }

    private Expr or() {
        Expr left = and();
        while (acceptKeyword("OR")) {
            left = new Binary(Op.OR, left, and());
        }
        return left;
    }

    private Expr and() {
        Expr left = not();
        while (acceptKeyword("AND")) {
            left = new Binary(Op.AND, left, not());
        }
        return left;
    }

    private Expr not() {
        if (acceptKeyword("NOT")) {
            return new Unary(UnaryOp.NOT, not());
        }
        return comparison();
    }

    private Expr comparison() {
        Expr left = additive();
        if (acceptKeyword("IS")) {
            boolean negated = acceptKeyword("NOT");
            expectKeyword("NULL");
            return new IsNull(left, negated);
        }
        Token t = peek();
        if (t.kind() == Kind.SYMBOL) {
            Op op = switch (t.text()) {
                case "=" -> Op.EQ;
                case "!=" -> Op.NE;
                case "<" -> Op.LT;
                case "<=" -> Op.LE;
                case ">" -> Op.GT;
                case ">=" -> Op.GE;
                default -> null;
            };
            if (op != null) {
                next();
                return new Binary(op, left, additive());
            }
        }
        return left;
    }

    private Expr additive() {
        Expr left = multiplicative();
        while (true) {
            if (acceptSymbol("+")) {
                left = new Binary(Op.ADD, left, multiplicative());
            } else if (acceptSymbol("-")) {
                left = new Binary(Op.SUB, left, multiplicative());
            } else {
                return left;
            }
        }
    }

    private Expr multiplicative() {
        Expr left = unary();
        while (true) {
            if (acceptSymbol("*")) {
                left = new Binary(Op.MUL, left, unary());
            } else if (acceptSymbol("/")) {
                left = new Binary(Op.DIV, left, unary());
            } else {
                return left;
            }
        }
    }

    private Expr unary() {
        if (acceptSymbol("-")) {
            Expr operand = unary();
            if (operand instanceof Literal l && l.value() instanceof Value.IntValue i) {
                return new Literal(Value.of(-i.value())); // fold negative literals so indexes can use them
            }
            return new Unary(UnaryOp.NEGATE, operand);
        }
        return primary();
    }

    private Expr primary() {
        Token t = next();
        switch (t.kind()) {
            case NUMBER -> {
                try {
                    return new Literal(Value.of(Long.parseLong(t.text())));
                } catch (NumberFormatException e) {
                    throw error("Number too large", t);
                }
            }
            case STRING -> {
                return new Literal(Value.of(t.text()));
            }
            case IDENT -> {
                if (t.isKeyword("NULL")) return new Literal(Value.NULL);
                if (t.isKeyword("TRUE")) return new Literal(Value.TRUE);
                if (t.isKeyword("FALSE")) return new Literal(Value.FALSE);
                if (RESERVED.contains(t.text().toUpperCase())) {
                    throw error("Expected a value or column name", t);
                }
                if (acceptSymbol(".")) {
                    return new ColumnRef(t.text(), identifier());
                }
                return new ColumnRef(null, t.text());
            }
            case SYMBOL -> {
                if (t.isSymbol("(")) {
                    Expr e = expression();
                    expectSymbol(")");
                    return e;
                }
            }
            default -> {
            }
        }
        throw error("Expected a value or column name", t);
    }

    // ---------------------------------------------------------------- token helpers

    private Token peek() {
        return tokens.get(pos);
    }

    private Token peek(int ahead) {
        return tokens.get(Math.min(pos + ahead, tokens.size() - 1));
    }

    private Token next() {
        Token t = tokens.get(pos);
        if (t.kind() != Kind.EOF) {
            pos++;
        }
        return t;
    }

    private boolean acceptKeyword(String keyword) {
        if (peek().isKeyword(keyword)) {
            pos++;
            return true;
        }
        return false;
    }

    private boolean acceptSymbol(String symbol) {
        if (peek().isSymbol(symbol)) {
            pos++;
            return true;
        }
        return false;
    }

    private void expectKeyword(String keyword) {
        if (!acceptKeyword(keyword)) {
            throw error("Expected " + keyword);
        }
    }

    private void expectSymbol(String symbol) {
        if (!acceptSymbol(symbol)) {
            throw error("Expected '" + symbol + "'");
        }
    }

    private String identifier() {
        Token t = next();
        if (t.kind() != Kind.IDENT) {
            throw error("Expected a name", t);
        }
        return t.text();
    }

    private SqlException error(String message) {
        return error(message, peek());
    }

    private SqlException error(String message, Token found) {
        return new SqlException(message + " but found " + found + " at position " + found.position());
    }
}
