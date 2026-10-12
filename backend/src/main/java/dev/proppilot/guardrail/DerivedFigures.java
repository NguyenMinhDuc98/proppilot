package dev.proppilot.guardrail;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Consumer;

/**
 * The figures that follow from what a tool result lists, and that a model writes although no result states them. Each
 * rule is one a reader would check by hand, and is applied with exact arithmetic:
 *
 * <ul>
 *   <li><b>A count:</b> for an array of objects, the number of rows that share a value of a scalar field, for a group
 *       of two or more rows (11 tenants that are 103 days late)</li>
 *   <li><b>A sum:</b> for an array of objects and a numeric field, the sum over all rows and the sum over each group
 *       of rows that share a value of another field (the four unpaid months of 4,800, 19,200)</li>
 *   <li><b>A rate:</b> a number above 0 and up to 1 that has a fractional part, times 100, rounded to 0, 1 and 2
 *       decimals as an answer prints it (0.5 written as 50%)</li>
 * </ul>
 *
 * Nothing else is derived: not a difference, a product, an average, or a sum of rows that nothing groups. A result
 * of another call is a table of its own: rows of two results are never added or counted together.
 *
 * <p>The work is bounded, so that a result of any size costs time in proportion to its length: no more than
 * {@value RowTable#MAX_ROWS} rows of an array and {@value RowTable#MAX_FIELDS} fields of a row are read, a field is
 * grouped by its first {@value #MAX_VALUES} values, nothing is read below {@value #MAX_DEPTH} levels of nesting, and a
 * run derives at most {@value #MAX_FIGURES} figures. An array of more rows than are read derives nothing, because a
 * sum or a count of some of its rows is no figure of it. The figures are given to the sink in their normal form, as
 * {@link ClaimScanner} writes one.
 */
final class DerivedFigures {

    /** An array and its rows are read when they lie within this many levels of containers. */
    static final int MAX_DEPTH = 6;
    static final int MAX_VALUES = 30;
    static final int MAX_FIGURES = 20_000;

    private static final int[] PRINTED_DECIMALS = {0, 1, 2};
    private static final BigDecimal PERCENT = BigDecimal.valueOf(100);

    private final Consumer<String> figures;
    private int left = MAX_FIGURES;

    DerivedFigures(Consumer<String> figures) {
        this.figures = figures;
    }

    /** Whether the limit is reached: nothing more is derived, so nothing more need be read for it. */
    boolean spent() {
        return left <= 0;
    }

    /** A rate that a tool returns as a fraction: {@code 0.857} is also {@code 86}, {@code 85.7}. */
    void ofRate(BigDecimal number) {
        boolean fraction = number.signum() > 0 && number.compareTo(BigDecimal.ONE) <= 0
                && number.stripTrailingZeros().scale() > 0;
        if (!fraction || spent()) {
            return;
        }
        var percent = number.multiply(PERCENT);
        for (int decimals : PRINTED_DECIMALS) {
            emit(percent.setScale(decimals, RoundingMode.HALF_UP));
        }
    }

    /** The counts and the sums of the rows of one array. */
    void ofRows(RowTable table) {
        var rows = table.rows();
        if (!table.isComplete() || rows.size() < 2 || spent()) {
            return;
        }
        var amounts = numericFields(table);
        for (var amount : amounts) {
            emit(sum(rows, amount));
        }
        for (var field : table.fields()) {
            for (var group : groupsSharing(rows, field)) {
                emit(BigDecimal.valueOf(group.size()));
                for (var amount : amounts) {
                    if (!amount.equals(field)) {
                        emit(sum(group, amount));
                    }
                }
            }
            if (spent()) {
                return;
            }
        }
    }

    private void emit(BigDecimal figure) {
        if (left > 0) {
            left--;
            // A figure has no sign in an answer, and ClaimScanner reads none in a result either.
            figures.accept(figure.abs().stripTrailingZeros().toPlainString());
        }
    }

    /** The fields whose values are all numbers (or null), so that a sum of them is an amount. */
    private static List<String> numericFields(RowTable table) {
        var numeric = new ArrayList<String>();
        for (var field : table.fields()) {
            boolean number = false;
            boolean other = false;
            for (var row : table.rows()) {
                var value = row.get(field);
                if (value instanceof BigDecimal) {
                    number = true;
                } else if (value != null && value != RowTable.NONE) {
                    other = true;
                }
            }
            if (number && !other) {
                numeric.add(field);
            }
        }
        return numeric;
    }

    /** The groups of two or more rows that have the same value of {@code field}, of its first values only. */
    private static List<List<RowTable.Row>> groupsSharing(List<RowTable.Row> rows, String field) {
        var groups = new LinkedHashMap<Object, List<RowTable.Row>>();
        for (var row : rows) {
            var value = row.get(field);
            if (value == null) {
                continue;
            }
            var group = groups.get(value);
            if (group == null && groups.size() < MAX_VALUES) {
                group = new ArrayList<>();
                groups.put(value, group);
            }
            if (group != null) {
                group.add(row);
            }
        }
        return groups.values().stream().filter(group -> group.size() >= 2).toList();
    }

    private static BigDecimal sum(List<RowTable.Row> rows, String field) {
        var total = BigDecimal.ZERO;
        for (var row : rows) {
            if (row.get(field) instanceof BigDecimal amount) {
                total = total.add(amount);
            }
        }
        return total;
    }
}
