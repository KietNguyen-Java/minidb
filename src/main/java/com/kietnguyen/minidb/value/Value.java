package com.kietnguyen.minidb.value;

import com.kietnguyen.minidb.SqlException;

import java.util.Objects;

/**
 * A single SQL value. Booleans are represented as INT 1 / 0, as in SQLite.
 */
public sealed interface Value extends Comparable<Value> permits Value.IntValue, Value.TextValue, Value.NullValue {

    NullValue NULL = new NullValue();
    IntValue TRUE = new IntValue(1);
    IntValue FALSE = new IntValue(0);

    static Value of(long value) {
        return new IntValue(value);
    }

    static Value of(String value) {
        return value == null ? NULL : new TextValue(value);
    }

    static Value bool(boolean value) {
        return value ? TRUE : FALSE;
    }

    /** The type of the value, or {@code null} for NULL. */
    DataType type();

    default boolean isNull() {
        return this instanceof NullValue;
    }

    /** NULL sorts before every other value. INT and TEXT cannot be compared with each other. */
    @Override
    default int compareTo(Value other) {
        if (this.isNull() || other.isNull()) {
            return Boolean.compare(!this.isNull(), !other.isNull());
        }
        if (this instanceof IntValue a && other instanceof IntValue b) {
            return Long.compare(a.value(), b.value());
        }
        if (this instanceof TextValue a && other instanceof TextValue b) {
            return a.value().compareTo(b.value());
        }
        throw new SqlException("Cannot compare " + type() + " with " + other.type());
    }

    /** How the value is written in SQL source, e.g. {@code 'O''Brien'}. */
    default String toSql() {
        return switch (this) {
            case TextValue t -> "'" + t.value().replace("'", "''") + "'";
            default -> toString();
        };
    }

    record IntValue(long value) implements Value {
        @Override
        public DataType type() {
            return DataType.INT;
        }

        @Override
        public String toString() {
            return Long.toString(value);
        }
    }

    record TextValue(String value) implements Value {
        public TextValue {
            Objects.requireNonNull(value);
        }

        @Override
        public DataType type() {
            return DataType.TEXT;
        }

        @Override
        public String toString() {
            return value;
        }
    }

    record NullValue() implements Value {
        @Override
        public DataType type() {
            return null;
        }

        @Override
        public String toString() {
            return "NULL";
        }
    }
}
