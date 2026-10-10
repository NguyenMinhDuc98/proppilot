package dev.proppilot.actions;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ActionErrorTest {

    @Test
    void codesAreLowerCaseSnakeCaseAndReadBackToTheirError() {
        assertThat(ActionError.AMOUNT_EXCEEDS_OUTSTANDING.code()).isEqualTo("amount_exceeds_outstanding");
        for (var error : ActionError.values()) {
            assertThat(ActionError.fromCode(error.code())).isEqualTo(error);
            assertThat(error.code()).matches("[a-z_]+");
        }
    }

    @Test
    void mapsEachKindOfRefusalToItsStatus() {
        assertThat(ActionError.INVALID_ACTION_ID.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ActionError.INVALID_REQUEST.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ActionError.INVALID_IDEMPOTENCY_KEY.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ActionError.ACTION_NOT_FOUND.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ActionError.ACTION_ALREADY_DECIDED.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ActionError.IDEMPOTENCY_KEY_REUSED.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ActionError.ACTION_NOT_DECIDED.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ActionError.ACTION_ALREADY_CONFIRMED.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ActionError.RUN_PROPOSAL_LIMIT_REACHED.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(ActionError.DAILY_PROPOSAL_LIMIT_REACHED.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(ActionError.ACTION_EXPIRED.status()).isEqualTo(HttpStatus.GONE);
        assertThat(ActionError.INVALID_AMOUNT.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(ActionError.NO_OVERDUE_RENT.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(ActionError.RATE_LIMITED.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(ActionError.DAILY_APPROVAL_LIMIT_REACHED.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }
}
