CREATE TABLE cities (
    id      BIGSERIAL PRIMARY KEY,
    name_en VARCHAR(80) NOT NULL UNIQUE,
    name_ar VARCHAR(80) NOT NULL
);

CREATE TABLE buildings (
    id      BIGSERIAL PRIMARY KEY,
    code    VARCHAR(8)   NOT NULL UNIQUE,
    name    VARCHAR(120) NOT NULL,
    city_id BIGINT       NOT NULL REFERENCES cities (id),
    address VARCHAR(200) NOT NULL
);

CREATE TABLE units (
    id           BIGSERIAL PRIMARY KEY,
    building_id  BIGINT        NOT NULL REFERENCES buildings (id),
    code         VARCHAR(16)   NOT NULL UNIQUE,
    floor        INT           NOT NULL,
    bedrooms     INT           NOT NULL,
    area_sqm     INT           NOT NULL,
    monthly_rent NUMERIC(10, 2) NOT NULL,
    status       VARCHAR(16)   NOT NULL CHECK (status IN ('VACANT', 'OCCUPIED', 'MAINTENANCE'))
);
CREATE INDEX idx_units_building ON units (building_id);
CREATE INDEX idx_units_status ON units (status);

CREATE TABLE tenants (
    id                 BIGSERIAL PRIMARY KEY,
    name_en            VARCHAR(120) NOT NULL,
    name_ar            VARCHAR(120) NOT NULL,
    phone              VARCHAR(24)  NOT NULL,
    email              VARCHAR(120) NOT NULL,
    preferred_language VARCHAR(2)   NOT NULL CHECK (preferred_language IN ('en', 'ar'))
);

CREATE TABLE leases (
    id           BIGSERIAL PRIMARY KEY,
    unit_id      BIGINT         NOT NULL REFERENCES units (id),
    tenant_id    BIGINT         NOT NULL REFERENCES tenants (id),
    start_date   DATE           NOT NULL,
    end_date     DATE           NOT NULL,
    monthly_rent NUMERIC(10, 2) NOT NULL,
    active       BOOLEAN        NOT NULL DEFAULT TRUE
);
CREATE INDEX idx_leases_unit ON leases (unit_id);
CREATE INDEX idx_leases_tenant ON leases (tenant_id);

-- One row per lease per month. paid_on IS NULL means not paid (yet).
CREATE TABLE payments (
    id          BIGSERIAL PRIMARY KEY,
    lease_id    BIGINT         NOT NULL REFERENCES leases (id),
    period      DATE           NOT NULL,
    due_date    DATE           NOT NULL,
    amount      NUMERIC(10, 2) NOT NULL,
    paid_amount NUMERIC(10, 2) NOT NULL DEFAULT 0,
    paid_on     DATE,
    UNIQUE (lease_id, period)
);
CREATE INDEX idx_payments_period ON payments (period);

CREATE TABLE chat_runs (
    id            UUID PRIMARY KEY,
    created_at    TIMESTAMPTZ    NOT NULL DEFAULT now(),
    question      VARCHAR(1000)  NOT NULL,
    provider      VARCHAR(24)    NOT NULL,
    model         VARCHAR(64)    NOT NULL,
    input_tokens  INT            NOT NULL,
    output_tokens INT            NOT NULL,
    tool_calls    INT            NOT NULL,
    iterations    INT            NOT NULL,
    latency_ms    BIGINT         NOT NULL,
    cost_usd      NUMERIC(12, 6) NOT NULL,
    status        VARCHAR(16)    NOT NULL CHECK (status IN ('OK', 'ERROR', 'MAX_ITERATIONS'))
);
CREATE INDEX idx_chat_runs_created ON chat_runs (created_at);
