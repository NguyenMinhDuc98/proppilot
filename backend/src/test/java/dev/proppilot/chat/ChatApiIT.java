package dev.proppilot.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.proppilot.admin.DemoResetService;
import dev.proppilot.rag.CitationRefs;
import dev.proppilot.rag.CorpusIntegrationTest;
import dev.proppilot.rag.DocumentLanguage;
import dev.proppilot.rag.pdf.PlantedInstruction;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * End to end through HTTP + SSE with the offline assistant (no API key, no cost), on the stored lease corpus.
 */
class ChatApiIT extends CorpusIntegrationTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    DemoResetService demo;

    @AfterEach
    void removeWhatTheQuestionsProposed() {
        demo.reset();
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    /** The seeded non-payer's unit: a payment and a rent reminder for it are valid. */
    private String unitWithOverdueRent() {
        return jdbc.queryForObject("select u.code from leases l join units u on u.id = l.unit_id where l.tenant_id = 7",
                String.class);
    }

    /** The payload of the first event with this name. */
    private JsonNode eventData(String sse, String name) throws Exception {
        var lines = sse.split("\n");
        for (int i = 0; i < lines.length - 1; i++) {
            if (lines[i].equals("event:" + name)) {
                return JSON.readTree(lines[i + 1].substring("data:".length()));
            }
        }
        throw new AssertionError("no " + name + " event in " + sse);
    }

    /** One server-sent event of a stream. */
    private record Event(String name, JsonNode data) {
    }

    /** Every event of the stream, in the order they were sent. */
    private List<Event> events(String sse) throws Exception {
        var events = new ArrayList<Event>();
        var lines = sse.split("\n");
        for (int i = 0; i < lines.length - 1; i++) {
            if (lines[i].startsWith("event:")) {
                events.add(new Event(lines[i].substring("event:".length()),
                        JSON.readTree(lines[i + 1].substring("data:".length()))));
            }
        }
        return events;
    }

    private static List<String> names(List<Event> events) {
        return events.stream().map(Event::name).distinct().toList();
    }

    private static List<String> fieldNames(JsonNode node) {
        var names = new ArrayList<String>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static List<String> markersIn(String text) {
        var markers = new ArrayList<String>();
        var matcher = CitationRefs.MARKER.matcher(text);
        while (matcher.find()) {
            markers.add(CitationRefs.parse(matcher.group().substring(1, matcher.group().length() - 1))
                    .orElseThrow().token());
        }
        return markers;
    }

    /** The unit of the first lease whose document is in this language, "en" or "ar". */
    private String unitWithADocument(String language) {
        return jdbc.queryForObject("""
                select u.code from lease_documents d join leases l on l.id = d.lease_id join units u on u.id = l.unit_id
                where d.language = ? order by d.lease_id limit 1""", String.class, language);
    }

    private String unitWithoutADocument() {
        return jdbc.queryForObject("""
                select u.code from leases l join units u on u.id = l.unit_id
                where l.active and l.id not in (select lease_id from lease_documents) order by l.id limit 1""",
                String.class);
    }

    /** Every question comes from a client of its own, so the limit on questions per client never applies. */
    private static final AtomicInteger CLIENTS = new AtomicInteger();

    private String chat(String body) throws Exception {
        var started = mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content(body)
                        .with(http -> {
                            http.setRemoteAddr("10.1.0." + CLIENTS.incrementAndGet());
                            return http;
                        }))
                .andExpect(request().asyncStarted()).andReturn();
        started.getAsyncResult(10_000);
        return mvc.perform(asyncDispatch(started)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /** Concatenates the text of all `token` events, i.e. the answer as the user sees it. */
    private String answerText(String sse) throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var answer = new StringBuilder();
        var lines = sse.split("\n");
        for (int i = 0; i < lines.length - 1; i++) {
            if (lines[i].equals("event:token")) {
                answer.append(json.readTree(lines[i + 1].substring("data:".length())).get("text").asText());
            }
        }
        return answer.toString();
    }

    @Test
    void streamsToolCallToolResultTokensAndDone() throws Exception {
        var sse = chat("{\"message\":\"Who is more than 30 days late on rent?\"}");

        assertThat(sse).contains("event:tool_call").contains("find_overdue_tenants")
                .contains("event:tool_result").contains("event:token")
                .contains("event:done").contains("\"status\":\"OK\"").contains("\"toolCalls\":1")
                .contains("\"provider\":\"offline\"").contains("\"truncated\":false");
        assertThat(answerText(sse)).contains("Ahmed Al-Harbi").doesNotContain("find_overdue_tenants").doesNotContain("Data:");
        assertThat(sse.indexOf("event:tool_call")).isLessThan(sse.indexOf("event:token"));
        assertThat(sse.indexOf("event:token")).isLessThan(sse.indexOf("event:done"));
    }

    @Test
    void answersInArabicForArabicQuestions() throws Exception {
        var sse = chat("{\"message\":\"من المستأجرين المتأخرين في الدفع؟\"}");

        assertThat(sse).contains("find_overdue_tenants");
        assertThat(answerText(sse)).contains("أحمد الحربي").doesNotContain("find_overdue_tenants").doesNotContain("البيانات من");
    }

    @Test
    void aWriteQuestionStreamsTheProposalAsAnActionCard() throws Exception {
        var unit = unitWithOverdueRent();

        var sse = chat("{\"message\":\"Record a payment of 100 for unit " + unit + "\"}");

        assertThat(sse).contains("event:tool_call").contains("record_payment").contains("event:tool_result")
                .contains("event:action_proposed").contains("event:done").contains("\"status\":\"OK\"");
        assertThat(sse.indexOf("event:tool_call")).isLessThan(sse.indexOf("event:tool_result"));
        assertThat(sse.indexOf("event:tool_result")).isLessThan(sse.indexOf("event:action_proposed"));
        assertThat(sse.indexOf("event:action_proposed")).isLessThan(sse.indexOf("event:token"));
        assertThat(sse.indexOf("event:token")).isLessThan(sse.indexOf("event:done"));

        var result = eventData(sse, "tool_result");
        assertThat(result.get("error").asBoolean()).isFalse();
        assertThat(result.get("summary").asText()).startsWith("Proposed, waiting for approval");
        var card = eventData(sse, "action_proposed");
        assertThat(card.get("id").asText()).matches("[0-9a-f-]{36}");
        assertThat(card.get("kind").asText()).isEqualTo("RECORD_PAYMENT");
        assertThat(card.get("status").asText()).isEqualTo("PROPOSED");
        assertThat(card.get("summaryEn").asText()).contains("SAR 100.00", unit);
        assertThat(card.get("summaryAr").asText()).contains("ريال", unit);
        assertThat(card.at("/preview/payments")).isNotEmpty();
        assertThat(card.get("expiresAt").asText()).isNotEmpty();

        assertThat(answerText(sse)).contains("PROPOSED", "waiting for your approval").contains("SAR 100.00");
        var runId = eventData(sse, "done").get("runId").asText();
        assertThat(jdbc.queryForObject("select run_id::text from pending_actions where id = ?::uuid", String.class,
                card.get("id").asText())).isEqualTo(runId);
        assertThat(jdbc.queryForObject("select count(*) from maintenance_requests", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from payments where recorded_action_id is not null",
                Integer.class)).isZero();
    }

    @Test
    void anArabicWriteQuestionIsAnsweredInArabicWithTheSameCard() throws Exception {
        var unit = unitWithOverdueRent();

        var sse = chat("{\"message\":\"سجّل دفعة بمبلغ 100 للوحدة " + unit + "\"}");

        assertThat(sse).contains("event:action_proposed").contains("record_payment");
        assertThat(eventData(sse, "action_proposed").get("status").asText()).isEqualTo("PROPOSED");
        assertThat(answerText(sse)).contains("بانتظار موافقتك", "مقترح").doesNotContain("record_payment");
    }

    @Test
    void aRefusedProposalStreamsAnErrorResultAndNoActionCard() throws Exception {
        var sse = chat("{\"message\":\"Create a maintenance request for unit F-999: broken lock\"}");

        assertThat(sse).contains("event:tool_result").doesNotContain("event:action_proposed").contains("event:done");
        assertThat(eventData(sse, "tool_result").get("error").asBoolean()).isTrue();
        assertThat(answerText(sse)).contains("I could not complete that").contains("There is no unit with code 'F-999'");
        assertThat(jdbc.queryForObject("select count(*) from pending_actions", Integer.class)).isZero();
    }

    @Test
    void aLeaseQuestionStreamsTheClausesItRestsOnWithItsResultAndAnswersWithTheirMarkers() throws Exception {
        var unit = unitWithADocument("en");

        var sse = chat("{\"message\":\"What is the late fee for unit " + unit + "?\"}");

        var events = events(sse);
        assertThat(names(events)).containsExactly("tool_call", "tool_result", "token", "done");
        var call = events.get(0).data();
        assertThat(call.get("name").asText()).isEqualTo("search_lease_documents");
        assertThat(call.at("/args/unit_code").asText()).isEqualTo(unit);
        var result = events.get(1).data();
        assertThat(result.get("error").asBoolean()).isFalse();
        var sources = result.get("sources");
        assertThat(sources.size()).isBetween(1, 2);
        assertThat(result.get("summary").asText()).isEqualTo(
                sources.size() + (sources.size() == 1 ? " clause" : " clauses") + " from 1 lease document");
        assertThat(fieldNames(sources.get(0))).containsExactly("ref", "documentCode", "documentTitle", "unitCode",
                "tenantNameEn", "tenantNameAr", "language", "sectionNo", "sectionTitle", "page", "text", "pdfPath");
        assertThat(sources.get(0).get("ref").asText()).isEqualTo("LSE-" + unit + "#5");
        assertThat(sources.get(0).get("unitCode").asText()).isEqualTo(unit);
        assertThat(sources.get(0).get("pdfPath").asText()).isEqualTo("/api/lease-documents/LSE-" + unit + "/pdf");
        var answer = answerText(sse);
        assertThat(answer).contains("matching clause(s) from 1 lease document(s):", "> ", "From unit " + unit);
        var refs = new ArrayList<String>();
        sources.forEach(source -> refs.add(source.get("ref").asText()));
        assertThat(markersIn(answer)).containsExactlyElementsOf(refs);
        assertThat(answer).doesNotContain("search_lease_documents");
        var done = events.get(events.size() - 1).data();
        assertThat(done.get("status").asText()).isEqualTo("OK");
        assertThat(done.get("toolCalls").asInt()).isEqualTo(1);
        assertThat(done.get("provider").asText()).isEqualTo("offline");
    }

    @Test
    void anArabicLeaseQuestionIsAnsweredInArabicWithTheSameSourcesAndMarkers() throws Exception {
        var unit = unitWithADocument("ar");

        var sse = chat("{\"message\":\"ما هي غرامة التأخير للوحدة " + unit + "؟\"}");

        var events = events(sse);
        assertThat(events.get(0).data().get("name").asText()).isEqualTo("search_lease_documents");
        assertThat(events.get(1).data().get("sources").get(0).get("ref").asText()).isEqualTo("LSE-" + unit + "#5");
        var answer = answerText(sse);
        assertThat(answer).contains("عدد البنود المطابقة", "من الوحدة " + unit, "وثيقة العقد LSE-" + unit)
                .doesNotContain("search_lease_documents");
        assertThat(markersIn(answer)).startsWith("LSE-" + unit + "#5");
    }

    @Test
    void otherToolsKeepTheShapeOfTheirResultEvent() throws Exception {
        var sse = chat("{\"message\":\"Who is more than 30 days late on rent?\"}");

        var result = events(sse).stream().filter(event -> event.name().equals("tool_result")).findFirst()
                .orElseThrow().data();
        assertThat(fieldNames(result)).containsExactly("name", "summary", "error");
    }

    @Test
    void aFailedLeaseSearchStreamsAnErrorResultWithNoSources() throws Exception {
        var sse = chat("{\"message\":\"What is the notice period for unit F-999?\"}");

        var result = eventData(sse, "tool_result");
        assertThat(result.get("error").asBoolean()).isTrue();
        assertThat(fieldNames(result)).containsExactly("name", "summary", "error");
        assertThat(answerText(sse)).contains("I could not complete that").contains("No unit with code 'F-999'");
    }

    @Test
    void aCombinedQuestionListsTheLeasesThatEndAndThenSearchesTheirDocuments() throws Exception {
        var sse = chat("{\"message\":\"Which leases end in the next 60 days and require 90 days notice?\"}");

        var events = events(sse);
        var tools = events.stream().filter(event -> event.name().equals("tool_call"))
                .map(event -> event.data().get("name").asText()).toList();
        assertThat(tools).containsExactly("find_expiring_leases", "search_lease_documents");
        var order = events.stream().map(Event::name).filter(name -> !name.equals("token")).toList();
        assertThat(order).containsExactly("tool_call", "tool_result", "tool_call", "tool_result", "done");
        var listCall = events.get(0).data();
        assertThat(listCall.at("/args/within_days").asInt()).isEqualTo(60);
        var listResult = events.get(1).data();
        assertThat(fieldNames(listResult)).containsExactly("name", "summary", "error");
        assertThat(listResult.get("summary").asText()).isEqualTo("7 leases end within 60 days");
        var searchCall = events.stream().filter(event -> event.name().equals("tool_call")).skip(1).findFirst()
                .orElseThrow().data();
        assertThat(searchCall.at("/args/unit_codes")).hasSize(7);
        assertThat(searchCall.at("/args/query").asText()).contains("notice period");
        var searchResult = events.stream().filter(event -> event.name().equals("tool_result")).skip(1).findFirst()
                .orElseThrow().data();
        assertThat(searchResult.get("sources")).isNotEmpty();
        var without = jdbc.queryForList("""
                select u.code from leases l join units u on u.id = l.unit_id
                where l.active and l.end_date between ? and ?
                  and l.id not in (select lease_id from lease_documents) order by l.end_date""", String.class,
                LocalDate.now(), LocalDate.now().plusDays(60));
        var answer = answerText(sse);
        assertThat(answer).startsWith("7 lease(s) end within the next 60 days.")
                .contains("| Unit | Tenant | Ends | Days left | Rent (SAR/mo) | Lease document |")
                .contains("No lease document is on file for unit(s): " + String.join(", ", without) + ".");
        var markers = markersIn(answer);
        assertThat(markers).isNotEmpty();
        var documented = jdbc.queryForList("""
                select u.code from leases l join units u on u.id = l.unit_id
                where l.active and l.end_date between ? and ?
                  and l.id in (select lease_id from lease_documents)""", String.class, LocalDate.now(),
                LocalDate.now().plusDays(60));
        assertThat(documented).isNotEmpty();
        assertThat(markers).as("the notice period clause of every lease that has a document")
                .containsAll(documented.stream().map(unit -> "LSE-" + unit + "#4").toList());
        var done = events.get(events.size() - 1).data();
        assertThat(done.get("toolCalls").asInt()).isEqualTo(2);
        assertThat(done.get("iterations").asInt()).isEqualTo(3);
    }

    @Test
    void aLeaseWithoutADocumentGetsAnHonestAnswerAndNoSources() throws Exception {
        var unit = unitWithoutADocument();

        var sse = chat("{\"message\":\"What is the notice period for unit " + unit + "?\"}");

        var result = eventData(sse, "tool_result");
        assertThat(result.get("error").asBoolean()).isFalse();
        assertThat(result.get("summary").asText()).isEqualTo("No matching lease clauses");
        assertThat(result.get("sources").isArray()).isTrue();
        assertThat(result.get("sources")).isEmpty();
        assertThat(answerText(sse)).isEqualTo("I found no clause in the lease documents that answers this.\n\n"
                + "No lease document is on file for unit(s): " + unit + ".");
    }

    @Test
    void aLeaseThatGivesInstructionsToAssistantsIsQuotedAndNothingIsProposed() throws Exception {
        var planted = jdbc.queryForMap("""
                select u.code as unit, t.preferred_language as language
                from leases l join units u on u.id = l.unit_id join tenants t on t.id = l.tenant_id
                where l.active order by l.id limit 1 offset 28""");
        var sentence = PlantedInstruction.of(DocumentLanguage.fromCode((String) planted.get("language")));

        var sse = chat("{\"message\":\"What are the special conditions of the lease of unit "
                + planted.get("unit") + "?\"}");

        var events = events(sse);
        assertThat(events.stream().filter(event -> event.name().equals("tool_call"))
                .map(event -> event.data().get("name").asText()).toList()).containsExactly("search_lease_documents");
        assertThat(sse).doesNotContain("event:action_proposed");
        assertThat(events.get(1).data().get("sources").get(0).get("text").asText()).contains(sentence);
        assertThat(answerText(sse)).contains("> ").contains(sentence);
        assertThat(jdbc.queryForObject("select count(*) from pending_actions", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from payments where recorded_action_id is not null",
                Integer.class)).isZero();
        assertThat(eventData(sse, "done").get("toolCalls").asInt()).isEqualTo(1);
    }

    @Test
    void readQuestionsStreamNoActionCard() throws Exception {
        var sse = chat("{\"message\":\"Who is more than 30 days late on rent?\"}");

        assertThat(sse).doesNotContain("action_proposed");
    }

    @Test
    void recordsRunsAndExposesStats() throws Exception {
        chat("{\"message\":\"Which units in Jeddah are vacant?\"}");

        mvc.perform(get("/api/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRuns").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.toolUsage[?(@.tool=='search_units')]").exists());
    }

    @Test
    void publicStatsNeverExposeWhatVisitorsAsked() throws Exception {
        chat("{\"message\":\"Which units in Dammam are vacant? (stats privacy marker)\"}");

        mvc.perform(get("/api/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recentRuns").doesNotExist())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("privacy marker"))));
    }

    @Test
    void rejectsBlankAndOversizedMessages() throws Exception {
        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"  \"}"))
                .andExpect(status().isBadRequest());
        var tooLong = "x".repeat(501);
        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"" + tooLong + "\"}"))
                .andExpect(status().isBadRequest());
    }

    /** {@code count} alternating turns, starting with the user, as the web app sends them. */
    private static String historyOf(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> "{\"role\":\"" + (i % 2 == 0 ? "user" : "assistant") + "\",\"text\":\"turn " + i + "\"}")
                .collect(Collectors.joining(",", "[", "]"));
    }

    @Test
    void acceptsAHistoryOfTwentyItems() throws Exception {
        var sse = chat("{\"message\":\"Which units in Riyadh are vacant?\",\"history\":" + historyOf(20) + "}");

        assertThat(sse).contains("event:done");
    }

    @Test
    void rejectsAHistoryLongerThanTwentyItems() throws Exception {
        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hi\",\"history\":" + historyOf(21) + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(status().reason("history must have at most 20 items"));
    }

    @Test
    void rejectsMalformedHistory() throws Exception {
        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hi\",\"history\":[{\"role\":\"user\"}]}"))
                .andExpect(status().isBadRequest());
    }
}
