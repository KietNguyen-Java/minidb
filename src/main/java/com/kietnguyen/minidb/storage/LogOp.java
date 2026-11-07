package com.kietnguyen.minidb.storage;

import com.kietnguyen.minidb.value.Value;

import java.util.List;

/**
 * One change to the database. A statement produces a list of these, which is written to the
 * write-ahead log as a single record and replayed in order when the database is opened.
 */
public sealed interface LogOp {

    record CreateTable(String table, List<Column> columns) implements LogOp {
    }

    record DropTable(String table) implements LogOp {
    }

    record CreateIndex(String index, String table, String column, boolean unique) implements LogOp {
    }

    record DropIndex(String index, String table) implements LogOp {
    }

    record Insert(String table, long rowId, Value[] values) implements LogOp {
    }

    record Delete(String table, long rowId) implements LogOp {
    }

    record Update(String table, long rowId, Value[] values) implements LogOp {
    }
}
