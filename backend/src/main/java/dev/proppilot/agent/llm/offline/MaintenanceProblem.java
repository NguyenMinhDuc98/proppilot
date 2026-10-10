package dev.proppilot.agent.llm.offline;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a request to open a maintenance request says about the problem, in English and Arabic: the description, the
 * kind of problem and how urgent it is.
 *
 * <p>The priority comes only from what the visitor said about priority: a phrase ("priority high", "high priority",
 * "أولوية عالية"), an urgent adjective on the request ("an urgent maintenance request"), or a level standing apart at
 * the edge of the description ("urgent: ...", "..., urgent"). A word inside the problem ("low water pressure", "high
 * ceiling") says nothing about priority. The kind of problem is read from the description alone. Patterns are in the
 * folded spelling of {@link Folding}.
 */
final class MaintenanceProblem {

    /** The shortest description the executor accepts. */
    private static final int MIN_DESCRIPTION_LENGTH = 5;
    private static final int FLAGS = Pattern.UNICODE_CHARACTER_CLASS;
    private static final String WORD_END = "(?![\\p{L}\\p{N}])";

    /** A stretch of the text that may hold the description: from the first position up to, not including, the second. */
    record Region(int from, int to) {
    }

    /** {@code description} is empty when the visitor gave none. */
    record Problem(String description, String category, String priority) {
    }

    private record Described(String text, String level) {

        boolean isUsable() {
            return !NO_CONTENT.matcher(Folding.fold(text)).matches();
        }
    }

    private record Category(String name, Pattern pattern) {

        Category(String name, String regex) {
            this(name, Pattern.compile(regex, FLAGS));
        }
    }

    /** The first category with a keyword wins, so "AC leaking water" is an HVAC problem and not a plumbing one. */
    private static final List<Category> CATEGORIES = List.of(
            new Category("HVAC", "\\b(?:hvac|a/c|ac|aircon|cooling|heating|ventilation|thermostat)\\b"
                    + "|air[- ]condition|تكييف|مكيف|تبريد|تدفئه|تهويه"),
            new Category("ELECTRICAL", "electric|\\b(?:power|outlet|socket|wiring|wire|light|lights|lamp|breaker|fuse)\\b"
                    + "|كهرب|انار|مصباح|اضاءه|اسلاك|فيوز"),
            new Category("APPLIANCE", "appliance|fridge|refrigerator|oven|stove|washer|washing machine|dishwasher|dryer"
                    + "|microwave|heater|ثلاجه|فرن|غساله|سخان|ميكروويف|(?<!\\p{L})جهاز"),
            new Category("PLUMBING", "plumb|leak|pipe|water|\\b(?:tap|faucet|drain\\w*|toilet|sink|shower|sewage|clog\\w*)\\b"
                    + "|سباك|تسرب|تسريب|ماسور|مواسير|مياه|حنفي|مجاري|تصريف|مرحاض"),
            new Category("STRUCTURAL", "crack|\\b(?:walls?|ceiling|roof|floor|foundation|structural|door|window)\\b"
                    + "|تشقق|شقوق|جدار|حائط|سقف|ارضي|(?<!\\p{L})باب(?!\\p{L})|نافذ|شباك|هيكل"),
            new Category("CLEANING", "clean|trash|garbage|نظاف|تنظيف|نظيف"));

    static final String URGENT_WORDS = "urgent|emergency|asap|critical|immediately|عاجله?|طاريه?|طواري"
            + "|مستعجله?|فورا|حرجه?|قصوي";
    private static final String HIGH_WORDS = "high|عاليه?|مرتفعه?";
    private static final String MEDIUM_WORDS = "medium|normal|متوسطه?|عاديه?";
    private static final String LOW_WORDS = "low|منخفضه?";
    private static final String LEVELS = URGENT_WORDS + "|" + HIGH_WORDS + "|" + MEDIUM_WORDS + "|" + LOW_WORDS;
    /** Urgent enough to be a priority without a separator: "urgent leaking pipe", not "emergency exit light". */
    private static final String SHOUTED = "urgent|asap|عاجله?|مستعجله?";

    private static final Pattern URGENT = Pattern.compile("(?:" + URGENT_WORDS + ")", FLAGS);
    private static final Pattern HIGH = Pattern.compile("(?:" + HIGH_WORDS + ")", FLAGS);
    private static final Pattern LOW = Pattern.compile("(?:" + LOW_WORDS + ")", FLAGS);
    private static final Pattern URGENT_ADJECTIVE = Pattern.compile("(?<![\\p{L}\\p{N}])(?:" + URGENT_WORDS + ")"
            + WORD_END, FLAGS);

    private static final Pattern PRIORITY_PHRASE = Pattern.compile(
            "(?<![\\p{L}\\p{N}])priority\\s*(?:[:=]|is\\b)?\\s*(?<a>" + LEVELS + ")" + WORD_END
                    + "|(?<![\\p{L}\\p{N}])(?<b>" + LEVELS + ")\\s+priority" + WORD_END
                    + "|(?:ب|ذات\\s+|ذو\\s+)?(?:ال)?اولويه\\s*[:=]?\\s*(?<c>" + LEVELS + ")" + WORD_END, FLAGS);
    private static final Pattern LEADING_LEVEL = Pattern.compile("^[\\s:,;.\\-–—،]*(?:(?<w>" + LEVELS
            + ")(?=\\s*[:;,،]|\\s+[-–—]\\s)|(?<s>" + SHOUTED + ")" + WORD_END + ")", FLAGS);
    private static final Pattern TRAILING_LEVEL = Pattern.compile("(?:\\s*[,;،]|\\s+[-–—])\\s*(?<w>" + LEVELS
            + ")[\\s.!]*$|\\s+(?:is\\s+)?(?<s>" + SHOUTED + ")[\\s.!]*$", FLAGS);

    private static final String UNIT_WORDS = "unit|apartment|flat|(?:لل|ل)?(?:ال)?(?:وحده|شقه)|(?:لل|ل)?(?:وحدت|شقت)[يك]";
    /**
     * The description is the {@code keep} group: without the word that introduces the problem ("about", "because of")
     * in front, and without the unit it is about ("in unit", "for my apartment", "في الوحدة") and punctuation behind.
     */
    private static final Pattern TIDY = Pattern.compile("^[\\s:,;.\\-–—،]*(?:(?:about|regarding|because(?:\\s+of)?"
            + "|saying|describing|for|بخصوص|بشان|بسبب|حول|عن)\\s+)*(?<keep>.*?)"
            + "(?:\\s*(?:(?:in|for|at|on|of|في)\\s+)?(?:(?:the|my|our|this|that)\\s+)?(?:" + UNIT_WORDS + ")\\s*)?"
            + "[\\s,;:.\\-–—،]*$", FLAGS | Pattern.DOTALL);
    /** Only words that point at the unit or the request itself, so there is no problem described. */
    private static final Pattern NO_CONTENT = Pattern.compile("(?:[^\\p{L}\\p{N}]*(?:for|in|at|on|of|to|the|a|an|my|our"
            + "|your|this|that|there|is|are|it|please|about|regarding|" + UNIT_WORDS + "|في|من|علي|الي|عن)" + WORD_END
            + ")*[^\\p{L}\\p{N}]*", FLAGS);

    private MaintenanceProblem() {
    }

    /**
     * @param commandStart where the command starts in the text, so a priority said in it is read too
     * @param bodyStart where the command ends and the rest of the request begins
     * @param regions where the description may be, most likely first
     */
    static Problem read(String text, String folded, int commandStart, int bodyStart, List<Region> regions) {
        var described = regions.stream().map(region -> describe(text, folded, region)).filter(Described::isUsable).toList();
        var chosen = described.stream().filter(d -> d.text().length() >= MIN_DESCRIPTION_LENGTH).findFirst()
                .or(() -> described.stream().findFirst());
        var description = chosen.map(Described::text).orElse("");
        return new Problem(description, category(Folding.fold(description)),
                priority(folded, commandStart, bodyStart, chosen.map(Described::level).orElse(null)));
    }

    private static String category(String foldedDescription) {
        return CATEGORIES.stream().filter(category -> category.pattern().matcher(foldedDescription).find())
                .map(Category::name).findFirst().orElse("OTHER");
    }

    private static String priority(String folded, int commandStart, int bodyStart, String atTheEdge) {
        var phrase = PRIORITY_PHRASE.matcher(folded).region(commandStart, folded.length());
        if (phrase.find()) {
            return levelOf(phrase);
        }
        if (URGENT_ADJECTIVE.matcher(folded).region(commandStart, bodyStart).find()) {
            return "URGENT";
        }
        return atTheEdge != null ? atTheEdge : "MEDIUM";
    }

    /** The region without a priority phrase or a level at its edge, tidied up; the level is what was left out. */
    private static Described describe(String text, String folded, Region region) {
        var kept = new StringBuilder();
        int copied = region.from();
        var phrase = PRIORITY_PHRASE.matcher(folded).region(region.from(), region.to());
        while (phrase.find()) {
            kept.append(text, copied, phrase.start());
            copied = phrase.end();
        }
        kept.append(text, copied, region.to());

        var description = kept.toString();
        var foldedDescription = Folding.fold(description);
        String level = null;
        var leading = LEADING_LEVEL.matcher(foldedDescription);
        if (leading.find()) {
            level = levelOf(leading);
            description = description.substring(leading.end());
            foldedDescription = foldedDescription.substring(leading.end());
        }
        var trailing = TRAILING_LEVEL.matcher(foldedDescription);
        if (trailing.find()) {
            level = level != null ? level : levelOf(trailing);
            description = description.substring(0, trailing.start());
            foldedDescription = foldedDescription.substring(0, trailing.start());
        }
        var tidy = TIDY.matcher(foldedDescription);
        // The keep group can take any text, so the pattern always matches.
        var tidied = tidy.matches() ? description.substring(tidy.start("keep"), tidy.end("keep")) : "";
        return new Described(tidied, level);
    }

    /** The level a priority match names: each of its alternatives has exactly one group, and only one took part. */
    private static String levelOf(Matcher match) {
        String word = null;
        for (int group = 1; group <= match.groupCount() && word == null; group++) {
            word = match.group(group);
        }
        if (URGENT.matcher(word).matches()) {
            return "URGENT";
        }
        if (HIGH.matcher(word).matches()) {
            return "HIGH";
        }
        return LOW.matcher(word).matches() ? "LOW" : "MEDIUM";
    }
}
