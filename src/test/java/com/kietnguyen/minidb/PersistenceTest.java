package com.kietnguyen.minidb;

import com.kietnguyen.minidb.value.Value;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistenceTest {

    @TempDir
    Path dir;

    @Test
    void dataSurvivesReopen() throws IOException {
        Path file = dir.resolve("shop.db");
        try (Database db = Database.open(file, false)) {
            db.execute("""
                    CREATE TABLE products (id INT PRIMARY KEY, name TEXT, price INT);
                    CREATE INDEX idx_price ON products(price);
                    INSERT INTO products VALUES (1, 'Ca phe', 25000), (2, 'Banh mi', 20000), (3, 'Tra', 15000);
                    UPDATE products SET price = 30000 WHERE id = 1;
                    DELETE FROM products WHERE id = 3;
                    """);
        }
        try (Database db = Database.open(file, false)) {
            assertEquals(Value.of(2), db.execute("SELECT COUNT(*) FROM products").scalar());
            assertEquals(Value.of(30000), db.execute("SELECT price FROM products WHERE id = 1").scalar());
            String plan = db.execute("EXPLAIN SELECT * FROM products WHERE price > 1").rows().toString();
            assertTrue(plan.contains("idx_price"), "index must be rebuilt on open: " + plan);
            // The primary key index is rebuilt too.
            assertThrows(SqlException.class, () -> db.execute("INSERT INTO products VALUES (2, 'X', 1)"));
            db.execute("INSERT INTO products VALUES (4, 'Nuoc mia', 10000)");
            assertEquals(Value.of(4), db.execute("SELECT id FROM products WHERE name = 'Nuoc mia'").scalar());
        }
    }

    @Test
    void failedStatementIsNotLogged() throws IOException {
        Path file = dir.resolve("t.db");
        try (Database db = Database.open(file, false)) {
            db.execute("CREATE TABLE t (id INT PRIMARY KEY)");
            db.execute("INSERT INTO t VALUES (1)");
            assertThrows(SqlException.class, () -> db.execute("INSERT INTO t VALUES (2), (1)"));
        }
        try (Database db = Database.open(file, false)) {
            assertEquals(Value.of(1), db.execute("SELECT COUNT(*) FROM t").scalar());
        }
    }

    @Test
    void recoversFromTornWrite() throws IOException {
        Path file = dir.resolve("crash.db");
        try (Database db = Database.open(file, false)) {
            db.execute("CREATE TABLE t (id INT PRIMARY KEY, note TEXT)");
            db.execute("INSERT INTO t VALUES (1, 'committed')");
            db.execute("INSERT INTO t VALUES (2, 'this write will be cut in half')");
        }
        long size = Files.size(file);
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.WRITE)) {
            ch.truncate(size - 10); // simulate power loss in the middle of the last append
        }
        try (Database db = Database.open(file, false)) {
            assertTrue(db.recoveredBytesDiscarded() > 0);
            assertEquals(Value.of(1), db.execute("SELECT COUNT(*) FROM t").scalar());
            db.execute("INSERT INTO t VALUES (2, 'retried')");
        }
        try (Database db = Database.open(file, false)) {
            assertEquals(Value.of("retried"), db.execute("SELECT note FROM t WHERE id = 2").scalar());
        }
    }

    @Test
    void detectsCorruptedRecordByChecksum() throws IOException {
        Path file = dir.resolve("bitflip.db");
        try (Database db = Database.open(file, false)) {
            db.execute("CREATE TABLE t (id INT)");
            db.execute("INSERT INTO t VALUES (1)");
        }
        byte[] bytes = Files.readAllBytes(file);
        bytes[bytes.length - 1] ^= 0x01; // flip one bit in the last record
        Files.write(file, bytes);
        try (Database db = Database.open(file, false)) {
            assertEquals(Value.of(0), db.execute("SELECT COUNT(*) FROM t").scalar());
        }
    }

    @Test
    void vacuumCompactsTheLog() throws IOException {
        Path file = dir.resolve("big.db");
        try (Database db = Database.open(file, false)) {
            db.execute("CREATE TABLE counter (id INT PRIMARY KEY, n INT)");
            db.execute("CREATE INDEX idx_n ON counter(n)");
            db.execute("INSERT INTO counter VALUES (1, 0)");
            for (int i = 0; i < 500; i++) {
                db.execute("UPDATE counter SET n = n + 1 WHERE id = 1");
            }
            long before = Files.size(file);
            db.execute("VACUUM");
            assertTrue(Files.size(file) < before / 10, "500 updates should compact to one row");
        }
        try (Database db = Database.open(file, false)) {
            assertEquals(Value.of(500), db.execute("SELECT n FROM counter WHERE id = 1").scalar());
            assertTrue(db.execute("EXPLAIN SELECT * FROM counter WHERE n = 500").rows().toString().contains("idx_n"));
        }
    }
}
