package com.kietnguyen.minidb.value;

import com.kietnguyen.minidb.SqlException;

public enum DataType {
    INT,
    TEXT;

    public static DataType parse(String name) {
        return switch (name.toUpperCase()) {
            case "INT", "INTEGER", "BIGINT" -> INT;
            case "TEXT", "VARCHAR", "STRING" -> TEXT;
            default -> throw new SqlException("Unknown column type: " + name);
        };
    }
}
