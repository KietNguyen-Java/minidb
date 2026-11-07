package com.kietnguyen.minidb;

import com.kietnguyen.minidb.sql.Ast;
import com.kietnguyen.minidb.sql.Ast.Binary;
import com.kietnguyen.minidb.sql.Ast.Op;
import com.kietnguyen.minidb.sql.Ast.Select;
import com.kietnguyen.minidb.sql.Lexer;
import com.kietnguyen.minidb.sql.Parser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParserTest {

    @Test
    void lexerHandlesQuotesCommentsAndOperators() {
        var tokens = Lexer.tokenize("SELECT 'it''s' -- comment\n FROM t WHERE a <> 1");
        assertEquals("it's", tokens.get(1).text());
        assertEquals("FROM", tokens.get(2).text());
        assertTrue(tokens.stream().anyMatch(t -> t.isSymbol("!=")), "<> is read as !=");
    }

    @Test
    void andBindsTighterThanOr() {
        Select s = (Select) Parser.parse("SELECT * FROM t WHERE a = 1 OR b = 2 AND c = 3");
        Binary or = assertInstanceOf(Binary.class, s.where());
        assertEquals(Op.OR, or.op());
        assertEquals(Op.AND, ((Binary) or.right()).op());
    }

    @Test
    void multiplicationBindsTighterThanAddition() {
        Select s = (Select) Parser.parse("SELECT a + b * 2 FROM t");
        assertEquals("(a + (b * 2))", Ast.format(s.items().getFirst().expr()));
    }

    @Test
    void parsesFullSelect() {
        Select s = (Select) Parser.parse(
                "SELECT u.name, o.total AS amount FROM users u JOIN orders o ON u.id = o.user_id "
                        + "WHERE o.total > 100 ORDER BY o.total DESC, u.name LIMIT 5");
        assertEquals("u", s.from().qualifier());
        assertEquals("orders", s.join().table().name());
        assertEquals("amount", s.items().get(1).alias());
        assertEquals(2, s.orderBy().size());
        assertTrue(s.orderBy().getFirst().descending());
        assertEquals(5L, s.limit());
    }

    @Test
    void parsesNegativeNumbersAsLiterals() {
        Select s = (Select) Parser.parse("SELECT * FROM t WHERE x > -5");
        assertEquals("(x > -5)", Ast.format(s.where()));
    }

    @Test
    void parsesSeveralStatements() {
        assertEquals(3, Parser.parseAll("CREATE TABLE t (a INT); INSERT INTO t VALUES (1);; SELECT * FROM t").size());
    }

    @Test
    void reportsWhereTheErrorIs() {
        SqlException e = assertThrows(SqlException.class, () -> Parser.parse("SELECT FROM t"));
        assertTrue(e.getMessage().contains("position 7"), e.getMessage());
        assertThrows(SqlException.class, () -> Parser.parse("SELECT * FROM t WHERE name = 'unterminated"));
        assertThrows(SqlException.class, () -> Parser.parse("CREATE TABLE t (a FLOAT)"));
    }
}
