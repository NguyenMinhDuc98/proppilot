-- Write actions with a human in the loop: the agent proposes a change, a person approves it, and every step is audited.

CREATE TABLE pending_actions (
    id              UUID PRIMARY KEY,
    kind            VARCHAR(32) NOT NULL
        CHECK (kind IN ('RECORD_PAYMENT', 'CREATE_MAINTENANCE_REQUEST', 'SEND_TENANT_REMINDER')),
    status          VARCHAR(16) NOT NULL
        CHECK (status IN ('PROPOSED', 'APPROVED', 'REJECTED', 'EXPIRED', 'FAILED')),
    args            JSONB       NOT NULL,
    preview         JSONB       NOT NULL,
    summary_en      TEXT        NOT NULL,
    summary_ar      TEXT        NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL,
    expires_at      TIMESTAMPTZ NOT NULL,
    decided_at      TIMESTAMPTZ,
    decision_reason TEXT,
    result          JSONB,
    idempotency_key VARCHAR(64) UNIQUE,
    confirmed_at    TIMESTAMPTZ,
    run_id          UUID,
    actor           VARCHAR(40)
);
CREATE INDEX idx_pending_actions_status ON pending_actions (status);
CREATE INDEX idx_pending_actions_created ON pending_actions (created_at);

CREATE TABLE maintenance_requests (
    id          BIGSERIAL PRIMARY KEY,
    unit_id     BIGINT       NOT NULL REFERENCES units (id),
    unit_code   VARCHAR(16)  NOT NULL,
    category    VARCHAR(16)  NOT NULL
        CHECK (category IN ('PLUMBING', 'ELECTRICAL', 'HVAC', 'APPLIANCE', 'STRUCTURAL', 'CLEANING', 'OTHER')),
    priority    VARCHAR(8)   NOT NULL CHECK (priority IN ('LOW', 'MEDIUM', 'HIGH', 'URGENT')),
    description VARCHAR(500) NOT NULL,
    status      VARCHAR(16)  NOT NULL DEFAULT 'OPEN',
    created_at  TIMESTAMPTZ  NOT NULL,
    action_id   UUID         NOT NULL UNIQUE REFERENCES pending_actions (id)
);

-- Nothing is ever really sent: a reminder waits here with delivered = false.
CREATE TABLE outbox_messages (
    id             UUID PRIMARY KEY,
    lease_id       BIGINT       NOT NULL REFERENCES leases (id),
    unit_code      VARCHAR(16)  NOT NULL,
    tenant_name_en VARCHAR(120) NOT NULL,
    tenant_name_ar VARCHAR(120) NOT NULL,
    language       VARCHAR(2)   NOT NULL CHECK (language IN ('en', 'ar')),
    purpose        VARCHAR(32)  NOT NULL
        CHECK (purpose IN ('RENT_REMINDER', 'LEASE_RENEWAL', 'MAINTENANCE_NOTICE')),
    subject        TEXT         NOT NULL,
    body           TEXT         NOT NULL,
    delivered      BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at     TIMESTAMPTZ  NOT NULL,
    action_id      UUID         NOT NULL UNIQUE REFERENCES pending_actions (id)
);

-- Rows point at their action loosely (no foreign key) so the history outlives the actions it describes.
CREATE TABLE audit_log (
    id          BIGSERIAL PRIMARY KEY,
    at          TIMESTAMPTZ NOT NULL,
    actor       VARCHAR(40) NOT NULL,
    event       VARCHAR(24) NOT NULL,
    action_id   UUID,
    entity_type VARCHAR(32) NOT NULL,
    entity_id   VARCHAR(64) NOT NULL,
    before      JSONB,
    after       JSONB,
    note        TEXT
);
CREATE INDEX idx_audit_log_at ON audit_log (at);

ALTER TABLE payments
    ADD COLUMN method VARCHAR(16) CHECK (method IN ('CASH', 'BANK_TRANSFER', 'CARD', 'CHEQUE')),
    ADD COLUMN recorded_action_id UUID;

-- What the nightly demo reset puts the payments back to.
CREATE TABLE payments_seed (
    id          BIGINT         PRIMARY KEY,
    paid_amount NUMERIC(10, 2) NOT NULL,
    paid_on     DATE
);
INSERT INTO payments_seed (id, paid_amount, paid_on)
SELECT id, paid_amount, paid_on FROM payments;
