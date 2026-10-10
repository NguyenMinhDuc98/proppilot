package dev.proppilot.actions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@AutoConfigureMockMvc
class ActionApiIT extends ActionIntegrationTest {

    private static final AtomicInteger NEXT_CLIENT = new AtomicInteger();

    @Autowired
    MockMvc mvc;

    private final String client = "198.51.100." + NEXT_CLIENT.incrementAndGet();

    private MockHttpServletRequestBuilder asClient(MockHttpServletRequestBuilder request, String address) {
        return request.with(r -> {
            r.setRemoteAddr(address);
            return r;
        });
    }

    private MockHttpServletRequestBuilder approve(UUID id, String key, String body) {
        var request = post("/api/actions/" + id + "/approve").contentType(MediaType.APPLICATION_JSON);
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        if (body != null) {
            request.content(body);
        }
        return asClient(request, client);
    }

    private MockHttpServletRequestBuilder postTo(String path, String body) {
        var request = post(path).contentType(MediaType.APPLICATION_JSON);
        if (body != null) {
            request.content(body);
        }
        return asClient(request, client);
    }

    @Test
    void listsTheLatestProposalsNewestFirstWithEverythingTheUiNeeds() throws Exception {
        var first = proposePayment("100");
        var second = proposePayment("200");

        mvc.perform(get("/api/actions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].id").value(second.id().toString()))
                .andExpect(jsonPath("$[1].id").value(first.id().toString()))
                .andExpect(jsonPath("$[0].kind").value("RECORD_PAYMENT"))
                .andExpect(jsonPath("$[0].status").value("PROPOSED"))
                .andExpect(jsonPath("$[0].summaryEn").isNotEmpty())
                .andExpect(jsonPath("$[0].summaryAr").isNotEmpty())
                .andExpect(jsonPath("$[0].args.unit_code").value(unit))
                .andExpect(jsonPath("$[0].preview.payments[0].paymentId").isNumber())
                .andExpect(jsonPath("$[0].expiresAt").isNotEmpty())
                .andExpect(jsonPath("$[0].idempotencyKey").doesNotExist());
    }

    @Test
    void returnsFiftyActionsAtMost() throws Exception {
        for (int i = 0; i < 52; i++) {
            proposePayment("1");
        }

        mvc.perform(get("/api/actions")).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(50)));
    }

    @Test
    void getsOneActionAndAnswersNotFoundWithTheErrorBody() throws Exception {
        var proposed = proposePayment("100");

        mvc.perform(get("/api/actions/" + proposed.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(proposed.id().toString()));
        mvc.perform(get("/api/actions/" + UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("action_not_found"))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void aMalformedIdIsABadRequestOnEveryEndpoint() throws Exception {
        for (String id : new String[] {"not-a-uuid", "1-1-1-1-1", "123", "00000000-0000-0000-0000-00000000000g"}) {
            mvc.perform(get("/api/actions/" + id))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_action_id"));
            mvc.perform(postTo("/api/actions/" + id + "/preview", null))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_action_id"));
            mvc.perform(postTo("/api/actions/" + id + "/approve", null).header("Idempotency-Key", "k1"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_action_id"));
            mvc.perform(postTo("/api/actions/" + id + "/reject", null))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_action_id"));
        }
    }

    @Test
    void approvingChangesTheDataAndNamesTheDeciderWithoutTheirAddress() throws Exception {
        var proposed = proposePayment("1000");

        mvc.perform(approve(proposed.id(), "key-1", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(false))
                .andExpect(jsonPath("$.action.status").value("APPROVED"))
                .andExpect(jsonPath("$.action.actor").value(matchesPattern("visitor-[0-9a-f]{8}")))
                .andExpect(jsonPath("$.action.actor").value(not(containsString(client))))
                .andExpect(jsonPath("$.action.result.applied").value(1000.0));

        assertThat(outstandingOfTenant7()).isEqualByComparingTo(rent.multiply(BigDecimal.valueOf(4)).subtract(new BigDecimal("1000")));
        var actor = jdbc.queryForObject("select actor from pending_actions where id = ?", String.class, proposed.id());
        assertThat(actor).matches("visitor-[0-9a-f]{8}");
        assertThat(audit().toString()).doesNotContain(client);
    }

    @Test
    void theSameVisitorKeepsOneNameAndAnotherGetsAnother() throws Exception {
        var actorOf = new java.util.function.Function<String, String>() {
            @Override
            public String apply(String address) {
                try {
                    var proposed = proposePayment("1");
                    var body = mvc.perform(asClient(post("/api/actions/" + proposed.id() + "/reject"), address))
                            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
                    return json.readTree(body).get("actor").asText();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
        };

        var first = actorOf.apply("203.0.113.10");

        assertThat(actorOf.apply("203.0.113.10")).isEqualTo(first);
        assertThat(actorOf.apply("203.0.113.11")).isNotEqualTo(first);
    }

    @Test
    void anApprovalNeedsAnIdempotencyKeyOfSafeCharacters() throws Exception {
        var proposed = proposePayment("1000");

        mvc.perform(approve(proposed.id(), null, null))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_idempotency_key"));
        mvc.perform(approve(proposed.id(), "bad key!", null))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_idempotency_key"));
        mvc.perform(approve(proposed.id(), "k".repeat(65), null))
                .andExpect(status().isBadRequest());
        assertThat(actions.get(proposed.id()).status()).isEqualTo(ActionStatus.PROPOSED);
    }

    @Test
    void repeatingTheKeyReplaysAndAnotherKeyConflicts() throws Exception {
        var proposed = proposePayment("1000");
        mvc.perform(approve(proposed.id(), "key-1", null)).andExpect(status().isOk());

        mvc.perform(approve(proposed.id(), "key-1", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.action.status").value("APPROVED"));
        mvc.perform(approve(proposed.id(), "key-2", null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("action_already_decided"));
        assertThat(count("audit_log")).as("RESET, PROPOSED and one APPROVED").isEqualTo(3);
    }

    @Test
    void anExpiredProposalIsGone() throws Exception {
        var proposed = proposePayment("1000");
        jdbc.update("update pending_actions set expires_at = now() - interval '1 second'");

        mvc.perform(approve(proposed.id(), "key-1", null))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("action_expired"));
        mvc.perform(get("/api/actions/" + proposed.id()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("EXPIRED"));
    }

    @Test
    void aProposalThatNoLongerHoldsAnswersUnprocessableAndIsMarkedFailed() throws Exception {
        var amount = rent.multiply(BigDecimal.valueOf(3)).toPlainString();
        var stale = proposePayment(amount);
        actions.approve(proposePayment(amount).id(), newKey(), edits(), "visitor-test");

        mvc.perform(approve(stale.id(), "key-1", null))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("amount_exceeds_outstanding"))
                .andExpect(jsonPath("$.message").isNotEmpty());
        mvc.perform(get("/api/actions/" + stale.id()))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.decisionReason").isNotEmpty());
    }

    @Test
    void anApprovalMayCarryEditsOfTheAmountAndTheOverrideOnly() throws Exception {
        var proposed = proposePayment("1000");

        mvc.perform(approve(proposed.id(), "key-1", "{\"unit_code\":\"A-101\"}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("invalid_argument"));
        mvc.perform(approve(proposed.id(), "key-1", "{\"amount\":\"lots\"}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("invalid_argument"));
        mvc.perform(approve(proposed.id(), "key-1", "{\"amount\":" + rent.multiply(BigDecimal.valueOf(5)) + "}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("amount_exceeds_outstanding"));

        mvc.perform(approve(proposed.id(), "key-1", "{\"amount\":1500.25,\"overridePayment\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action.args.amount").value(1500.25))
                .andExpect(jsonPath("$.action.result.applied").value(1500.25));
    }

    @Test
    void aBodyThatIsNotAJsonObjectIsABadRequest() throws Exception {
        var proposed = proposePayment("1000");

        for (String body : new String[] {"[1,2]", "\"text\"", "{not json"}) {
            mvc.perform(approve(proposed.id(), "key-1", body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_request"));
            mvc.perform(postTo("/api/actions/" + proposed.id() + "/preview", body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_request"));
            mvc.perform(postTo("/api/actions/" + proposed.id() + "/reject", body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_request"));
        }
        assertThat(actions.get(proposed.id()).status()).isEqualTo(ActionStatus.PROPOSED);
    }

    @Test
    void aBodyThatIsNotJsonAtAllIsABadRequestToo() throws Exception {
        var proposed = proposePayment("1000");

        mvc.perform(approve(proposed.id(), "key-1", "amount=5").contentType(MediaType.TEXT_PLAIN))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_request"));
    }

    @Test
    void previewRecomputesTheDiffWithEditsAndChangesNothing() throws Exception {
        var proposed = proposePayment("1000");
        var invoices = invoicesOfTenant7();

        mvc.perform(postTo("/api/actions/" + proposed.id() + "/preview", "{\"amount\":" + rent.add(BigDecimal.ONE) + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(proposed.id().toString()))
                .andExpect(jsonPath("$.args.amount").value(rent.add(BigDecimal.ONE).doubleValue()))
                .andExpect(jsonPath("$.preview.payments", hasSize(2)))
                .andExpect(jsonPath("$.summaryEn").isNotEmpty());
        mvc.perform(postTo("/api/actions/" + proposed.id() + "/preview", "{\"amount\":-5}"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(postTo("/api/actions/" + proposed.id() + "/preview", null))
                .andExpect(status().isOk()).andExpect(jsonPath("$.args.amount").value(1000.0));
        assertThat(invoicesOfTenant7()).isEqualTo(invoices);
    }

    @Test
    void rejectsWithAReasonOrWithoutOne() throws Exception {
        var withReason = proposePayment("1000");
        var without = proposePayment("2000");

        mvc.perform(postTo("/api/actions/" + withReason.id() + "/reject", "{\"reason\":\"wrong tenant\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.decisionReason").value("wrong tenant"));
        mvc.perform(postTo("/api/actions/" + without.id() + "/reject", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.decisionReason").doesNotExist());
        mvc.perform(postTo("/api/actions/" + withReason.id() + "/reject", "{\"reason\":42}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_request"));
        mvc.perform(approve(withReason.id(), "key-1", null)).andExpect(status().isConflict());
    }

    @Test
    void aTooLongRejectionReasonIsUnprocessable() throws Exception {
        var proposed = proposePayment("1000");

        mvc.perform(postTo("/api/actions/" + proposed.id() + "/reject", "{\"reason\":\"" + "x".repeat(201) + "\"}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("invalid_argument"));
    }

    @Test
    void theAuditLogListsTheLatestHundredEntriesNewestFirst() throws Exception {
        var proposed = proposePayment("1000");
        mvc.perform(approve(proposed.id(), "key-1", null)).andExpect(status().isOk());

        mvc.perform(get("/api/audit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].event").value("APPROVED"))
                .andExpect(jsonPath("$[0].actor").value(matchesPattern("visitor-[0-9a-f]{8}")))
                .andExpect(jsonPath("$[0].actionId").value(proposed.id().toString()))
                .andExpect(jsonPath("$[0].entityType").value("LEASE_PAYMENTS"))
                .andExpect(jsonPath("$[0].before").isArray())
                .andExpect(jsonPath("$[0].after[0].paidAmount").value(1000.0))
                .andExpect(jsonPath("$[0].at").isNotEmpty())
                .andExpect(jsonPath("$[1].event").value("PROPOSED"))
                .andExpect(jsonPath("$[1].actor").value("agent"));

        jdbc.update("""
                insert into audit_log (at, actor, event, entity_type, entity_id)
                select now() - interval '1 hour' - n * interval '1 second', 'agent', 'PROPOSED', 'PENDING_ACTION', 'old-' || n
                from generate_series(1, 150) n""");
        mvc.perform(get("/api/audit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(100)))
                .andExpect(jsonPath("$[0].event").value("APPROVED"));
    }

    @Test
    void theBrowserMayCallApproveAcrossOriginsWithItsKeyHeader() throws Exception {
        var proposed = proposePayment("1000");

        mvc.perform(options("/api/actions/" + proposed.id() + "/approve")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "idempotency-key,content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Headers", containsString("idempotency-key")));
    }

    @Test
    void errorsAreNeverShownAsAnHtmlOrFrameworkPage() throws Exception {
        mvc.perform(post("/api/actions/" + UUID.randomUUID() + "/approve").header("Idempotency-Key", "k")
                        .with(r -> {
                            r.setRemoteAddr(client);
                            return r;
                        }))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }
}
