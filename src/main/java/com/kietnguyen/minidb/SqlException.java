package com.kietnguyen.minidb;

/** Any error the user can fix by changing their SQL or their data. */
public class SqlException extends RuntimeException {
    public SqlException(String message) {
        super(message);
    }
}
