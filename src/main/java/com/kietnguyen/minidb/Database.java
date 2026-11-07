package com.kietnguyen.minidb;

import com.kietnguyen.minidb.exec.ExprCompiler;
import com.kietnguyen.minidb.exec.ExprCompiler.BoundExpr;
import com.kietnguyen.minidb.exec.ExprCompiler.ColumnName;
import com.kietnguyen.minidb.exec.Plan;
import com.kietnguyen.minidb.exec.Planner;
import com.kietnguyen.minidb.sql.Ast;
import com.kietnguyen.minidb.sql.Ast.Statement;
import com.kietnguyen.minidb.sql.Parser;
import com.kietnguyen.minidb.storage.Column;
import com.kietnguyen.minidb.storage.Index;
import com.kietnguyen.minidb.storage.LogOp;
import com.kietnguyen.minidb.storage.Table;
import com.kietnguyen.minidb.storage.WriteAheadLog;
import com.kietnguyen.minidb.value.Value;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The entry point: parses SQL, plans and runs it, and keeps the tables.
 *
 * <p>Each statement is atomic. Its changes are applied one by one while an undo list records how
 * to reverse each one; if any change fails (for example a duplicate primary key on the third row of
 * an INSERT), the applied changes are undone in reverse order and nothing reaches the log. Only a
 * statement that succeeds completely is appended to the write-ahead log.
 *
 * <p>The class is synchronized: one statement runs at a time.
 */
public final class Database implements Closeable {

    private final Map<String, Table> tables = new LinkedHashMap<>();
    private final Planner planner = new Planner(this::table);
    private final WriteAheadLog log; // null for an in-memory database
    private final long recoveredBytesDiscarded;

    private Database(WriteAheadLog log, long recoveredBytesDiscarded) {
        this.log = log;
        this.recoveredBytesDiscarded = recoveredBytesDiscarded;
    }

    /** A database that lives only in memory. */
    public static Database inMemory() {
        return new Database(null, 0);
    }

    /**
     * Opens (or creates) a database file and replays its log.
     *
     * @param fsync force every commit to disk before returning. Safer, slower.
     */
    public static Database open(Path file, boolean fsync) throws IOException {
        List<WriteAheadLog.Recovery> recovery = new ArrayList<>();
        WriteAheadLog log = WriteAheadLog.open(file, fsync, recovery);
        Database db = new Database(log, recovery.getFirst().discardedBytes());
        for (List<LogOp> record : recovery.getFirst().records()) {
            for (LogOp op : record) {
                db.apply(op);
            }
        }
        return db;
    }

    /** Bytes of a damaged last record that were dropped when the file was opened. */
    public long recoveredBytesDiscarded() {
        return recoveredBytesDiscarded;
    }

    /** Runs one or more statements and returns the result of the last one. */
    public synchronized Result execute(String sql) {
        Result last = Result.message("OK");
        for (Statement statement : Parser.parseAll(sql)) {
            last = execute(statement);
        }
        return last;
    }

    public synchronized List<String> tableNames() {
        return tables.values().stream().map(Table::name).toList();
    }

    @Override
    public synchronized void close() throws IOException {
        if (log != null) {
            log.close();
        }
    }

    Table table(String name) {
        Table t = tables.get(name.toLowerCase());
        if (t == null) {
            throw new SqlException("No such table: " + name);
        }
        return t;
    }

    // ---------------------------------------------------------------- statements

    private Result execute(Statement statement) {
        return switch (statement) {
            case Ast.Select s -> select(s);
            case Ast.Explain e -> explain(e);
            case Ast.CreateTable c -> createTable(c);
            case Ast.CreateIndex c -> {
                Table t = table(c.table());
                String column = t.columns().get(t.requireColumn(c.column())).name();
                commit(List.of(new LogOp.CreateIndex(c.name(), t.name(), column, c.unique())));
                yield Result.message("Index " + c.name() + " created on " + t.name() + "(" + column + ")");
            }
            case Ast.DropTable d -> {
                Table t = table(d.name());
                commit(List.of(new LogOp.DropTable(t.name())));
                yield Result.message("Table " + t.name() + " dropped");
            }
            case Ast.Insert i -> insert(i);
            case Ast.Update u -> update(u);
            case Ast.Delete d -> delete(d);
            case Ast.Vacuum v -> vacuum();
            case Ast.ShowTables s -> showTables();
        };
    }

    private Result select(Ast.Select select) {
        Plan.Node plan = planner.plan(select);
        List<Value[]> rows = plan.execute();
        List<String> columns = plan.columns().stream().map(ColumnName::name).toList();
        List<List<Value>> out = rows.stream().map(Arrays::asList).toList();
        return new Result(columns, out, "(" + rows.size() + (rows.size() == 1 ? " row)" : " rows)"));
    }

    private Result explain(Ast.Explain explain) {
        StringBuilder text = new StringBuilder();
        planner.plan(explain.select()).explain(text, 0);
        List<List<Value>> lines = text.toString().lines().map(l -> List.<Value>of(Value.of(l))).toList();
        return new Result(List.of("plan"), lines, "");
    }

    private Result createTable(Ast.CreateTable c) {
        if (tables.containsKey(c.name().toLowerCase())) {
            throw new SqlException("Table " + c.name() + " already exists");
        }
        Set<String> seen = new HashSet<>();
        long primaryKeys = c.columns().stream().filter(Ast.ColumnDef::primaryKey).count();
        if (primaryKeys > 1) {
            throw new SqlException("A table can have only one PRIMARY KEY column");
        }
        List<Column> columns = new ArrayList<>();
        for (Ast.ColumnDef def : c.columns()) {
            if (!seen.add(def.name().toLowerCase())) {
                throw new SqlException("Duplicate column name " + def.name());
            }
            columns.add(new Column(def.name(), def.type(), def.primaryKey(), def.notNull()));
        }
        commit(List.of(new LogOp.CreateTable(c.name(), columns)));
        return Result.message("Table " + c.name() + " created");
    }

    private Result insert(Ast.Insert insert) {
        Table t = table(insert.table());
        int[] targets;
        if (insert.columns() == null) {
            targets = new int[t.columns().size()];
            Arrays.setAll(targets, i -> i);
        } else {
            targets = insert.columns().stream().mapToInt(t::requireColumn).toArray();
            if (Arrays.stream(targets).distinct().count() != targets.length) {
                throw new SqlException("A column is listed twice in INSERT");
            }
        }
        List<LogOp> ops = new ArrayList<>();
        for (List<Ast.Expr> exprs : insert.rows()) {
            if (exprs.size() != targets.length) {
                throw new SqlException("Expected " + targets.length + " values but got " + exprs.size());
            }
            Value[] values = new Value[t.columns().size()];
            Arrays.fill(values, Value.NULL);
            for (int i = 0; i < targets.length; i++) {
                values[targets[i]] = ExprCompiler.compile(exprs.get(i), List.of()).eval(new Value[0]);
            }
            ops.add(new LogOp.Insert(t.name(), t.allocateRowId(), values));
        }
        commit(ops);
        return Result.message(ops.size() + (ops.size() == 1 ? " row inserted" : " rows inserted"));
    }

    private Result update(Ast.Update update) {
        Table t = table(update.table());
        List<ColumnName> columns = Plan.columnsOf(t);
        int[] targets = new int[update.assignments().size()];
        List<BoundExpr> values = new ArrayList<>();
        for (int i = 0; i < targets.length; i++) {
            targets[i] = t.requireColumn(update.assignments().get(i).column());
            values.add(ExprCompiler.compile(update.assignments().get(i).value(), columns));
        }
        List<LogOp> ops = new ArrayList<>();
        for (long rowId : matchingRows(t, update.where())) {
            Value[] old = t.row(rowId);
            Value[] next = old.clone();
            for (int i = 0; i < targets.length; i++) {
                next[targets[i]] = values.get(i).eval(old); // every SET sees the old row, as in standard SQL
            }
            ops.add(new LogOp.Update(t.name(), rowId, next));
        }
        commit(ops);
        return Result.message(ops.size() + (ops.size() == 1 ? " row updated" : " rows updated"));
    }

    private Result delete(Ast.Delete delete) {
        Table t = table(delete.table());
        List<LogOp> ops = new ArrayList<>();
        for (long rowId : matchingRows(t, delete.where())) {
            ops.add(new LogOp.Delete(t.name(), rowId));
        }
        commit(ops);
        return Result.message(ops.size() + (ops.size() == 1 ? " row deleted" : " rows deleted"));
    }

    /** Row ids of {@code t} that satisfy {@code where}, read through an index when possible. */
    private List<Long> matchingRows(Table t, Ast.Expr where) {
        Plan.Scan scan = planner.accessPath(t, t.name(), where, List.of());
        if (where == null) {
            return scan.rowIds();
        }
        BoundExpr test = ExprCompiler.compile(where, Plan.columnsOf(t));
        return scan.rowIds().stream().filter(id -> ExprCompiler.isTrue(test.eval(t.row(id)))).toList();
    }

    private Result vacuum() {
        if (log == null) {
            return Result.message("In-memory database: nothing to compact");
        }
        long before = log.sizeInBytes();
        List<LogOp> snapshot = new ArrayList<>();
        for (Table t : tables.values()) {
            snapshot.add(new LogOp.CreateTable(t.name(), t.columns()));
            for (Index idx : t.indexes()) {
                if (!t.columns().get(idx.column()).primaryKey()) {
                    snapshot.add(new LogOp.CreateIndex(idx.name(), t.name(), t.columns().get(idx.column()).name(), idx.unique()));
                }
            }
            for (Map.Entry<Long, Value[]> row : t.snapshot()) {
                snapshot.add(new LogOp.Insert(t.name(), row.getKey(), row.getValue()));
            }
        }
        log.rewrite(snapshot);
        return Result.message("Log compacted from " + before + " to " + log.sizeInBytes() + " bytes");
    }

    private Result showTables() {
        List<List<Value>> rows = tables.values().stream()
                .map(t -> List.of(Value.of(t.name()), Value.of(t.rowCount()), Value.of(t.indexes().size())))
                .toList();
        return new Result(List.of("table", "rows", "indexes"), rows, "(" + rows.size() + " tables)");
    }

    // ---------------------------------------------------------------- applying changes

    /**
     * Applies all ops or none: on failure the ops already applied are reversed with their undo ops.
     * The log is written only after every op succeeded.
     */
    private void commit(List<LogOp> ops) {
        List<LogOp> undo = new ArrayList<>();
        try {
            for (LogOp op : ops) {
                undo.add(apply(op));
            }
            if (log != null && !ops.isEmpty()) {
                log.append(ops);
            }
        } catch (RuntimeException e) {
            for (int i = undo.size() - 1; i >= 0; i--) {
                apply(undo.get(i));
            }
            throw e;
        }
    }

    /** Applies one change and returns the change that reverses it. */
    private LogOp apply(LogOp op) {
        return switch (op) {
            case LogOp.CreateTable c -> {
                Table t = new Table(c.table(), c.columns());
                for (int i = 0; i < c.columns().size(); i++) {
                    if (c.columns().get(i).primaryKey()) {
                        t.addIndex(new Index("pk_" + c.table(), i, true));
                    }
                }
                tables.put(c.table().toLowerCase(), t);
                yield new LogOp.DropTable(c.table());
            }
            case LogOp.DropTable d -> {
                Table removed = tables.remove(d.table().toLowerCase());
                // Dropping is the last op of its statement, so its undo never runs; recreate the schema anyway.
                yield new LogOp.CreateTable(removed.name(), removed.columns());
            }
            case LogOp.CreateIndex c -> {
                for (Table other : tables.values()) {
                    if (other.index(c.index()) != null) {
                        throw new SqlException("Index " + c.index() + " already exists");
                    }
                }
                Table t = table(c.table());
                t.addIndex(new Index(c.index(), t.requireColumn(c.column()), c.unique()));
                yield new LogOp.DropIndex(c.index(), c.table());
            }
            case LogOp.DropIndex d -> {
                table(d.table()).dropIndex(d.index());
                yield new LogOp.DropIndex(d.index(), d.table()); // never undone: only used as an undo op
            }
            case LogOp.Insert i -> {
                table(i.table()).insert(i.rowId(), i.values());
                yield new LogOp.Delete(i.table(), i.rowId());
            }
            case LogOp.Delete d -> {
                Value[] old = table(d.table()).delete(d.rowId());
                yield new LogOp.Insert(d.table(), d.rowId(), old);
            }
            case LogOp.Update u -> {
                Value[] old = table(u.table()).update(u.rowId(), u.values());
                yield new LogOp.Update(u.table(), u.rowId(), old);
            }
        };
    }
}
