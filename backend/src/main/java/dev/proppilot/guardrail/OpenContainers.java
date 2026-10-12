package dev.proppilot.guardrail;

import java.util.ArrayDeque;
import java.util.function.IntConsumer;

/**
 * The arrays and objects of a tool result that are open at a point of its read, for the two things taken from its
 * structure: the length of every array, and the rows of every array of objects, which are handed to
 * {@link DerivedFigures} when the array ends. It is fed the tokens of the result in order, as a stack, so that a
 * document of any depth costs no recursion.
 */
final class OpenContainers {

    private final ArrayDeque<Frame> open = new ArrayDeque<>();
    private final IntConsumer arrayLengths;
    private final DerivedFigures derived;

    OpenContainers(IntConsumer arrayLengths, DerivedFigures derived) {
        this.arrayLengths = arrayLengths;
        this.derived = derived;
    }

    void startArray() {
        countElement();
        // The array and the objects in it must lie within the depth that is read.
        boolean read = open.size() + 2 <= DerivedFigures.MAX_DEPTH && !derived.spent();
        open.push(new Frame(read ? new RowTable() : null, null));
    }

    void startObject() {
        countElement();
        var parent = open.peek();
        var row = parent == null || parent.table == null ? null : parent.table.newRow();
        open.push(new Frame(null, row));
    }

    /** A string, number, boolean or null: an element of an array, or the value of {@code field} of an object. */
    void scalar(String field, Object value) {
        countElement();
        var top = open.peek();
        if (top != null && top.row != null) {
            top.row.put(field, value);
        }
    }

    void endArray() {
        var array = open.pop();
        arrayLengths.accept(array.length);
        if (array.table != null) {
            derived.ofRows(array.table);
        }
    }

    void endObject() {
        open.pop();
    }

    private void countElement() {
        if (!open.isEmpty()) {
            open.peek().length++;
        }
    }

    /** An open array has its length and, when it is read for rows, its table; an open object may be a row. */
    private static final class Frame {

        private final RowTable table;
        private final RowTable.Row row;
        private int length;

        private Frame(RowTable table, RowTable.Row row) {
            this.table = table;
            this.row = row;
        }
    }
}
