package dev.proppilot.rag.retrieve;

import java.util.List;

/** Questions made to break a text search query, or the parser of one. */
final class HostileQuestions {

    static final List<String> ALL = List.of(
            "early termination | & ! ( ) <-> :* fee",
            "notice & !period",
            "'; drop table lease_chunks; --",
            "\"; select pg_sleep(10); --",
            "\\",
            "\\\\\\\\ ' \" ` \\x00",
            "((((((((((",
            "))))))))))",
            "'",
            "\"\"\"",
            ":*",
            "<->",
            "a:*b",
            "a:AB",
            "!!!!!!!!!!",
            "!?".repeat(250),
            "؟،؛".repeat(150),
            ".".repeat(500),
            "the of and a in to",
            "في من على",
            "a b c d e f g",
            "%",
            "_",
            "%%%___",
            "\u0000",
            "x\u0000y",
            "\ud800",
            "😀😀😀",
            "a".repeat(500),
            "ا".repeat(500),
            "word ".repeat(100),
            "1 2 3 4 5 6 7 8 9 0",
            "\n\u0000\t",
            "غرامةـــالتأخيرًٌٍ",
            "<script>alert(1)</script>",
            "{\"query\":\"x\"}",
            "$1 $2 ?",
            "[1,2,3]",
            "{1,2,3}");

    private HostileQuestions() {
    }
}
