package dev.proppilot.guardrail;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The objects of one array of a tool result, cut down to what a figure is derived from: at most {@value #MAX_ROWS}
 * rows, and in the table and in each row the first {@value #MAX_FIELDS} scalar fields. A value is a
 * {@link java.math.BigDecimal} (a number, without trailing zeros, so that {@code 4.8E+3} and {@code 4800} are one
 * value), a {@code String}, a {@code Boolean} or {@link #NONE} for a JSON null. An object or an array inside a row is
 * not a field of it.
 */
final class RowTable {

    static final int MAX_ROWS = 500;
    static final int MAX_FIELDS = 20;
    /** The value of a field that is null, which is not the same as a row that has no such field. */
    static final Object NONE = new Object();

    private final List<Row> rows = new ArrayList<>();
    private final Set<String> fields = new LinkedHashSet<>();
    private boolean overflowed;

    /** The row for the next object of the array, or null when the array has more objects than a table holds. */
    Row newRow() {
        if (rows.size() == MAX_ROWS) {
            overflowed = true;
            return null;
        }
        var row = new Row();
        rows.add(row);
        return row;
    }

    List<Row> rows() {
        return rows;
    }

    /** Whether the table holds every object of its array: a sum of some of the rows is no sum of the array. */
    boolean isComplete() {
        return !overflowed;
    }

    /** The names of the fields, in the order they were first read. */
    Set<String> fields() {
        return fields;
    }

    final class Row {

        private final Map<String, Object> values = new LinkedHashMap<>();

        /** Keeps the first value of a field when an object repeats its name, and nothing beyond the field limits. */
        void put(String field, Object value) {
            boolean known = fields.contains(field);
            if (values.containsKey(field) || values.size() == MAX_FIELDS || (!known && fields.size() == MAX_FIELDS)) {
                return;
            }
            fields.add(field);
            values.put(field, value);
        }

        /** The value of the field, or null when the row has none. */
        Object get(String field) {
            return values.get(field);
        }
    }
}
