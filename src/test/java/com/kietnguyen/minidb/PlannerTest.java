package com.kietnguyen.minidb;

import com.kietnguyen.minidb.value.Value;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlannerTest {

    private Database db;

    @BeforeEach
    void setUp() {
        db = Database.inMemory();
        db.execute("""
                CREATE TABLE users (id INT PRIMARY KEY, email TEXT NOT NULL, age INT);
                CREATE UNIQUE INDEX idx_users_email ON users(email);
                CREATE INDEX idx_users_age ON users(age);
                CREATE TABLE orders (id INT PRIMARY KEY, user_id INT, total INT);
                """);
    }

    private String explain(String select) {
        return db.execute("EXPLAIN " + select).rows().stream()
                .map(r -> r.getFirst().toString())
                .collect(Collectors.joining("\n"));
    }

    @Test
    void usesPrimaryKeyForEquality() {
        assertTrue(explain("SELECT * FROM users WHERE id = 5").contains("IndexLookup users using pk_users"));
    }

    @Test
    void prefersUniqueEqualityOverRange() {
        String plan = explain("SELECT * FROM users WHERE age > 18 AND email = 'a@b.vn'");
        assertTrue(plan.contains("IndexLookup users using idx_users_email"), plan);
    }

    @Test
    void mergesRangeConditionsOnOneColumn() {
        String plan = explain("SELECT * FROM users WHERE age >= 18 AND age < 30 AND age > 10");
        assertTrue(plan.contains("age in [18, 30)"), plan);
    }

    @Test
    void flipsConstantOnTheLeft() {
        String plan = explain("SELECT * FROM users WHERE 30 > age");
        assertTrue(plan.contains("age in (-inf, 30)"), plan);
    }

    @Test
    void fallsBackToFullScan() {
        assertTrue(explain("SELECT * FROM users WHERE age != 3").contains("FullScan users"));
        assertTrue(explain("SELECT * FROM users WHERE age + 1 = 3").contains("FullScan users"));
        assertTrue(explain("SELECT * FROM users WHERE age = 1 OR id = 2").contains("FullScan users"));
    }

    @Test
    void choosesJoinAlgorithmByAvailableIndex() {
        String hash = explain("SELECT * FROM users u JOIN orders o ON u.id = o.user_id");
        assertTrue(hash.contains("HashJoin"), hash);

        db.execute("CREATE INDEX idx_orders_user ON orders(user_id)");
        String indexed = explain("SELECT * FROM users u JOIN orders o ON u.id = o.user_id");
        assertTrue(indexed.contains("IndexNestedLoopJoin"), indexed);
        assertFalse(indexed.contains("HashJoin"), indexed);

        String nested = explain("SELECT * FROM users u JOIN orders o ON o.total > u.age");
        assertTrue(nested.contains("NestedLoopJoin"), nested);
    }

    @Test
    void indexScanReadsOnlyMatchingRows() {
        StringBuilder insert = new StringBuilder("INSERT INTO users VALUES ");
        for (int i = 1; i <= 2_000; i++) {
            insert.append(i > 1 ? "," : "").append("(").append(i).append(", 'u").append(i).append("@x.vn', ")
                    .append(i % 80).append(")");
        }
        db.execute(insert.toString());
        assertTrue(explain("SELECT * FROM users WHERE age = 7").contains("IndexLookup"));
        Value count = db.execute("SELECT COUNT(*) FROM users WHERE age = 7").scalar();
        assertTrue(count.equals(Value.of(25)), "2000 rows / 80 ages = 25 per age, got " + count);
    }
}
