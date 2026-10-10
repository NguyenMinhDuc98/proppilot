package dev.proppilot.actions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** The public demo's caps through HTTP: three decision calls per minute per client and one approval per day. */
@AutoConfigureMockMvc
@SpringBootTest(properties = {"proppilot.actions.decisions-per-minute=3", "proppilot.actions.max-approvals-per-day=1"})
class ActionDecisionLimitsApiIT extends ActionIntegrationTest {

    @Autowired
    MockMvc mvc;

    private static MockHttpServletRequestBuilder from(MockHttpServletRequestBuilder request, String address) {
        return request.with(r -> {
            r.setRemoteAddr(address);
            return r;
        });
    }

    @Test
    void aClientGetsThreeDecisionCallsPerMinuteAndThenAClearAnswer() throws Exception {
        var proposed = proposePayment("100");
        var path = "/api/actions/" + proposed.id();

        for (int i = 0; i < 3; i++) {
            mvc.perform(from(post(path + "/preview"), "203.0.113.50")).andExpect(status().isOk());
        }
        mvc.perform(from(post(path + "/preview"), "203.0.113.50"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("rate_limited"))
                .andExpect(jsonPath("$.message").isNotEmpty());
        mvc.perform(from(post(path + "/approve").header("Idempotency-Key", "k1"), "203.0.113.50"))
                .andExpect(status().isTooManyRequests());
        mvc.perform(from(post(path + "/reject"), "203.0.113.50"))
                .andExpect(status().isTooManyRequests());

        mvc.perform(from(post(path + "/preview"), "203.0.113.51")).andExpect(status().isOk());
        mvc.perform(from(get(path), "203.0.113.50")).andExpect(status().isOk());
        mvc.perform(from(get("/api/actions"), "203.0.113.50")).andExpect(status().isOk());
        assertThat(actions.get(proposed.id()).status()).as("the refused calls changed nothing").isEqualTo(ActionStatus.PROPOSED);
    }

    @Test
    void theDailyCapOnApprovalsAnswersTooManyRequestsWithItsOwnCode() throws Exception {
        var first = proposePayment("100");
        var second = proposePayment("100");

        mvc.perform(from(post("/api/actions/" + first.id() + "/approve").header("Idempotency-Key", "k1"), "203.0.113.60"))
                .andExpect(status().isOk());
        mvc.perform(from(post("/api/actions/" + second.id() + "/approve").header("Idempotency-Key", "k2"), "203.0.113.61"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("daily_approval_limit_reached"));
        assertThat(actions.get(second.id()).status()).isEqualTo(ActionStatus.PROPOSED);
    }
}
