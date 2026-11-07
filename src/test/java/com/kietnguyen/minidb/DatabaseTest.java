package com.kietnguyen.minidb;

import com.kietnguyen.minidb.value.Value;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseTest {

    private Database db;

    @BeforeEach
    void setUp() {
        db = Database.inMemory();
        db.execute("""
                CREATE TABLE users (id INT PRIMARY KEY, name TEXT NOT NULL, age INT, city TEXT);
                INSERT INTO users VALUES
                  (1, 'An', 22, 'Da Nang'),
                  (2, 'Binh', 30, 'Hue'),
                  (3, 'Chi', 27, 'Da Nang'),
                  (4, 'Dung', NULL, 'Ha Noi'),
                  (5, 'Em', 19, 'Da Nang');
                CREATE TABLE orders (id INT PRIMARY KEY, user_id INT NOT NULL, total INT NOT NULL);
                INSERT INTO orders VALUES (10, 1, 150), (11, 1, 90), (12, 3, 300), (13, 2, 50), (14, 9, 70);
                """);
    }

    private List<List<String>> rows(String sql) {
        return db.execute(sql).rows().stream()
                .map(r -> r.stream().map(Value::toString).toList())
                .toList();
    }

    private List<String> column(String sql) {
        return rows(sql).stream().map(List::getFirst).toList();
    }

    @Test
    void selectsWithWhereOrderAndLimit() {
        assertEquals(List.of("Chi", "An", "Em"),
                column("SELECT name FROM users WHERE city = 'Da Nang' ORDER BY age DESC"));
        assertEquals(List.of("Em", "An"), column("SELECT name FROM users WHERE age IS NOT NULL ORDER BY age LIMIT 2"));
    }

    @Test
    void comparisonWithNullIsNeverTrue() {
        assertEquals(List.of("Binh", "Chi"), column("SELECT name FROM users WHERE age > 25 ORDER BY name"));
        assertEquals(List.of("Dung"), column("SELECT name FROM users WHERE age IS NULL"));
        assertEquals(List.of(), column("SELECT name FROM users WHERE age = NULL"));
        assertEquals(List.of("An", "Binh", "Chi", "Em"),
                column("SELECT name FROM users WHERE NOT (age < 0) ORDER BY name"));
    }

    @Test
    void evaluatesExpressionsAndAliases() {
        Result r = db.execute("SELECT name, age + 1 AS next_year FROM users WHERE id = 1");
        assertEquals(List.of("name", "next_year"), r.columns());
        assertEquals(List.of(List.of("An", "23")), rows("SELECT name, age + 1 FROM users WHERE id = 1"));
    }

    @Test
    void countsRows() {
        assertEquals(Value.of(3), db.execute("SELECT COUNT(*) FROM users WHERE city = 'Da Nang'").scalar());
    }

    @Test
    void joinsTwoTables() {
        assertEquals(List.of(List.of("Chi", "300"), List.of("An", "150"), List.of("An", "90"), List.of("Binh", "50")),
                rows("SELECT u.name, o.total FROM users u JOIN orders o ON u.id = o.user_id ORDER BY o.total DESC"));
    }

    @Test
    void joinConditionCanBeWrittenEitherWay() {
        assertEquals(rows("SELECT u.name FROM users u JOIN orders o ON u.id = o.user_id ORDER BY o.id"),
                rows("SELECT u.name FROM users u JOIN orders o ON o.user_id = u.id ORDER BY o.id"));
    }

    @Test
    void nonEqualityJoinFallsBackToNestedLoop() {
        assertEquals(List.of(List.of("Em", "50")),
                rows("SELECT u.name, o.total FROM users u JOIN orders o ON o.total < u.age + 40 WHERE u.id = 5"));
    }

    @Test
    void updatesAndDeletes() {
        assertEquals("2 rows updated", db.execute("UPDATE users SET age = age + 1 WHERE city = 'Da Nang' AND age < 25").message());
        assertEquals(List.of("23", "20"), column("SELECT age FROM users WHERE id = 1 OR id = 5 ORDER BY id"));

        assertEquals("1 row deleted", db.execute("DELETE FROM users WHERE name = 'Binh'").message());
        assertEquals(Value.of(4), db.execute("SELECT COUNT(*) FROM users").scalar());
    }

    @Test
    void updateSeesTheOldRow() {
        db.execute("CREATE TABLE p (a INT, b INT); INSERT INTO p VALUES (1, 2)");
        db.execute("UPDATE p SET a = b, b = a");
        assertEquals(List.of(List.of("2", "1")), rows("SELECT a, b FROM p"));
    }

    @Test
    void insertWithColumnListFillsOthersWithNull() {
        db.execute("INSERT INTO users (id, name) VALUES (6, 'Giang')");
        assertEquals(List.of(List.of("Giang", "NULL")), rows("SELECT name, city FROM users WHERE id = 6"));
    }

    @Test
    void enforcesConstraints() {
        assertMessage("Duplicate value 1 for primary key", "INSERT INTO users VALUES (1, 'X', 1, 'Y')");
        assertMessage("cannot be NULL", "INSERT INTO users VALUES (7, NULL, 1, 'Y')");
        assertMessage("expects INT", "INSERT INTO users VALUES ('seven', 'X', 1, 'Y')");
        assertMessage("No such table", "SELECT * FROM nope");
        assertMessage("Unknown column", "SELECT salary FROM users");
        assertMessage("ambiguous", "SELECT id FROM users u JOIN orders o ON u.id = o.user_id");
        assertMessage("Division by zero", "SELECT age / 0 FROM users WHERE id = 1");
    }

    @Test
    void failedStatementChangesNothing() {
        // The third row breaks the primary key, so the first two must not be kept either.
        assertThrows(SqlException.class,
                () -> db.execute("INSERT INTO users VALUES (20, 'A', 1, 'X'), (21, 'B', 2, 'X'), (1, 'C', 3, 'X')"));
        assertEquals(Value.of(5), db.execute("SELECT COUNT(*) FROM users").scalar());

        // Setting every id to 1 fails on the second row; the first must be rolled back.
        assertThrows(SqlException.class, () -> db.execute("UPDATE users SET id = 1"));
        assertEquals(List.of("1", "2", "3", "4", "5"), column("SELECT id FROM users ORDER BY id"));
    }

    @Test
    void uniqueIndexRejectsDuplicates() {
        db.execute("CREATE UNIQUE INDEX idx_order_user ON orders(id)");
        assertMessage("duplicate value 1", "CREATE UNIQUE INDEX idx_u ON orders(user_id)");
        db.execute("CREATE UNIQUE INDEX idx_user_name ON users(name)");
        assertMessage("unique index idx_user_name", "INSERT INTO users VALUES (8, 'An', 1, 'X')");
    }

    @Test
    void indexedAndUnindexedQueriesAgree() {
        String[] queries = {
                "SELECT name FROM users WHERE age > 20 ORDER BY name",
                "SELECT name FROM users WHERE age >= 22 AND age < 30 ORDER BY name",
                "SELECT name FROM users WHERE 27 = age",
                "SELECT name FROM users WHERE age <= 22 ORDER BY name",
        };
        List<List<List<String>>> before = java.util.Arrays.stream(queries).map(this::rows).toList();
        db.execute("CREATE INDEX idx_users_age ON users(age)");
        List<List<List<String>>> after = java.util.Arrays.stream(queries).map(this::rows).toList();
        assertEquals(before, after);
    }

    @Test
    void showsTablesAndDropsThem() {
        assertEquals(List.of("users", "orders"), column("SHOW TABLES"));
        db.execute("DROP TABLE orders");
        assertEquals(List.of("users"), db.tableNames());
    }

    private void assertMessage(String expectedPart, String sql) {
        SqlException e = assertThrows(SqlException.class, () -> db.execute(sql));
        assertTrue(e.getMessage().toLowerCase().contains(expectedPart.toLowerCase()),
                "expected '" + expectedPart + "' in: " + e.getMessage());
    }
}
