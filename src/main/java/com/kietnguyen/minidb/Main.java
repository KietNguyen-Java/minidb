package com.kietnguyen.minidb;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/** Interactive shell: {@code java -jar minidb.jar [file.db]}. */
public final class Main {

    private static final String HELP = """
            Type SQL statements ending with ';'. Examples:
              CREATE TABLE users (id INT PRIMARY KEY, name TEXT NOT NULL, age INT);
              CREATE INDEX idx_users_age ON users(age);
              INSERT INTO users VALUES (1, 'An', 22), (2, 'Binh', 30);
              SELECT name FROM users WHERE age >= 25 ORDER BY name LIMIT 10;
              EXPLAIN SELECT * FROM users WHERE age > 20;
            Commands: .tables  .help  .quit""";

    private Main() {
    }

    public static void main(String[] args) throws IOException {
        Path file = Path.of(args.length > 0 ? args[0] : "minidb.db");
        try (Database db = Database.open(file, true)) {
            System.out.println("MiniDB 1.0 - database file: " + file.toAbsolutePath());
            if (db.recoveredBytesDiscarded() > 0) {
                System.out.println("Recovered from an interrupted write: discarded "
                        + db.recoveredBytesDiscarded() + " bytes of an incomplete statement.");
            }
            System.out.println("Type .help for examples.");
            repl(db);
        }
    }

    private static void repl(Database db) throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        StringBuilder buffer = new StringBuilder();
        while (true) {
            System.out.print(buffer.isEmpty() ? "minidb> " : "   ...> ");
            System.out.flush();
            String line = in.readLine();
            if (line == null) {
                return;
            }
            String trimmed = line.trim();
            if (buffer.isEmpty() && trimmed.startsWith(".")) {
                switch (trimmed) {
                    case ".quit", ".exit" -> {
                        return;
                    }
                    case ".help" -> System.out.println(HELP);
                    case ".tables" -> System.out.println(db.execute("SHOW TABLES").format());
                    default -> System.out.println("Unknown command " + trimmed + ". Type .help");
                }
                continue;
            }
            buffer.append(line).append('\n');
            if (trimmed.endsWith(";")) {
                run(db, buffer.toString());
                buffer.setLength(0);
            }
        }
    }

    private static void run(Database db, String sql) {
        long start = System.nanoTime();
        try {
            Result result = db.execute(sql);
            double ms = (System.nanoTime() - start) / 1_000_000.0;
            System.out.println(result.format() + String.format(" [%.2f ms]", ms));
        } catch (SqlException e) {
            System.out.println("Error: " + e.getMessage());
        }
    }
}
