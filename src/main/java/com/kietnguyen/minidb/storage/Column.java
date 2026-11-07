package com.kietnguyen.minidb.storage;

import com.kietnguyen.minidb.value.DataType;

public record Column(String name, DataType type, boolean primaryKey, boolean notNull) {
}
