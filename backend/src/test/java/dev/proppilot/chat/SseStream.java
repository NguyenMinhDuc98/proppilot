package dev.proppilot.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;

/** The events of a server-sent event stream that a test read as text. */
record SseStream(List<Event> events) {

    private static final ObjectMapper JSON = new ObjectMapper();

    record Event(String name, JsonNode data) {
    }

    static SseStream of(String sse) throws Exception {
        var events = new ArrayList<Event>();
        var lines = sse.split("\n");
        for (int i = 0; i < lines.length - 1; i++) {
            if (lines[i].startsWith("event:")) {
                events.add(new Event(lines[i].substring("event:".length()),
                        JSON.readTree(lines[i + 1].substring("data:".length()))));
            }
        }
        return new SseStream(events);
    }

    /** The names of the events in order, with each run of {@code token} events as one. */
    List<String> order() {
        var names = new ArrayList<String>();
        for (var event : events) {
            if (names.isEmpty() || !event.name().equals("token") || !names.get(names.size() - 1).equals("token")) {
                names.add(event.name());
            }
        }
        return names;
    }

    JsonNode first(String name) {
        return events.stream().filter(event -> event.name().equals(name)).findFirst().orElseThrow().data();
    }

    boolean has(String name) {
        return events.stream().anyMatch(event -> event.name().equals(name));
    }

    /** The text of the {@code token} events after the last {@code revise} event, which is the answer a reader sees. */
    String answer() {
        var answer = new StringBuilder();
        for (var event : events) {
            if (event.name().equals("revise")) {
                answer.setLength(0);
            } else if (event.name().equals("token")) {
                answer.append(event.data().get("text").asText());
            }
        }
        return answer.toString();
    }
}
