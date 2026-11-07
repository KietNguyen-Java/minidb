package com.kietnguyen.minidb;

import com.kietnguyen.minidb.value.Value;

import java.util.List;

/** The outcome of one statement: a table of rows for SELECT, otherwise just a message. */
public record Result(List<String> columns, List<List<Value>> rows, String message) {

    public static Result message(String message) {
        return new Result(List.of(), List.of(), message);
    }

    public boolean hasRows() {
        return !columns.isEmpty();
    }

    /** The single value of a one-row, one-column result, e.g. from {@code SELECT COUNT(*)}. */
    public Value scalar() {
        if (rows.size() != 1 || rows.getFirst().size() != 1) {
            throw new IllegalStateException("Result is not a single value: " + rows.size() + " rows");
        }
        return rows.getFirst().getFirst();
    }

    /** Renders the result as an ASCII table, like the mysql or psql command-line clients. */
    public String format() {
        if (!hasRows()) {
            return message;
        }
        int[] widths = new int[columns.size()];
        for (int i = 0; i < widths.length; i++) {
            widths[i] = columns.get(i).length();
        }
        for (List<Value> row : rows) {
            for (int i = 0; i < widths.length; i++) {
                widths[i] = Math.max(widths[i], row.get(i).toString().length());
            }
        }
        StringBuilder out = new StringBuilder();
        String border = border(widths);
        out.append(border).append(line(columns, widths)).append(border);
        for (List<Value> row : rows) {
            out.append(line(row.stream().map(Value::toString).toList(), widths));
        }
        if (!rows.isEmpty()) {
            out.append(border);
        }
        out.append(message);
        return out.toString();
    }

    private static String border(int[] widths) {
        StringBuilder b = new StringBuilder("+");
        for (int w : widths) {
            b.append("-".repeat(w + 2)).append('+');
        }
        return b.append('\n').toString();
    }

    private static String line(List<String> cells, int[] widths) {
        StringBuilder b = new StringBuilder("|");
        for (int i = 0; i < widths.length; i++) {
            b.append(' ').append(cells.get(i)).append(" ".repeat(widths[i] - cells.get(i).length() + 1)).append('|');
        }
        return b.append('\n').toString();
    }
}
