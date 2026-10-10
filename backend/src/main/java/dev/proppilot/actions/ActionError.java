package dev.proppilot.actions;

import java.util.Locale;
import org.springframework.http.HttpStatus;

/** Why an action request was refused: a stable code for clients to localise, and the HTTP status it maps to. */
public enum ActionError {
    INVALID_REQUEST(HttpStatus.BAD_REQUEST),
    INVALID_ACTION_ID(HttpStatus.BAD_REQUEST),
    INVALID_IDEMPOTENCY_KEY(HttpStatus.BAD_REQUEST),
    ACTION_NOT_FOUND(HttpStatus.NOT_FOUND),
    ACTION_ALREADY_DECIDED(HttpStatus.CONFLICT),
    ACTION_NOT_DECIDED(HttpStatus.CONFLICT),
    ACTION_ALREADY_CONFIRMED(HttpStatus.CONFLICT),
    IDEMPOTENCY_KEY_REUSED(HttpStatus.CONFLICT),
    ACTION_EXPIRED(HttpStatus.GONE),

    // The arguments do not describe a change that may be made.
    INVALID_ARGUMENT(HttpStatus.UNPROCESSABLE_ENTITY),
    INVALID_AMOUNT(HttpStatus.UNPROCESSABLE_ENTITY),
    UNKNOWN_UNIT(HttpStatus.UNPROCESSABLE_ENTITY),
    TENANT_NOT_FOUND(HttpStatus.UNPROCESSABLE_ENTITY),
    AMBIGUOUS_TENANT(HttpStatus.UNPROCESSABLE_ENTITY),
    TENANT_UNIT_MISMATCH(HttpStatus.UNPROCESSABLE_ENTITY),
    NO_ACTIVE_LEASE(HttpStatus.UNPROCESSABLE_ENTITY),
    PAYMENT_DATE_IN_FUTURE(HttpStatus.UNPROCESSABLE_ENTITY),
    PAYMENT_BEFORE_LEASE_START(HttpStatus.UNPROCESSABLE_ENTITY),
    NOTHING_OUTSTANDING(HttpStatus.UNPROCESSABLE_ENTITY),
    AMOUNT_EXCEEDS_OUTSTANDING(HttpStatus.UNPROCESSABLE_ENTITY),
    NO_OVERDUE_RENT(HttpStatus.UNPROCESSABLE_ENTITY),

    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS),
    DAILY_APPROVAL_LIMIT_REACHED(HttpStatus.TOO_MANY_REQUESTS),
    RUN_PROPOSAL_LIMIT_REACHED(HttpStatus.TOO_MANY_REQUESTS),
    DAILY_PROPOSAL_LIMIT_REACHED(HttpStatus.TOO_MANY_REQUESTS);

    private final HttpStatus status;

    ActionError(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }

    /** The wire format, for example {@code amount_exceeds_outstanding}. */
    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }

    static ActionError fromCode(String code) {
        return valueOf(code.toUpperCase(Locale.ROOT));
    }
}
