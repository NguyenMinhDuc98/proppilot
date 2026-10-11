-- Stored traces: what a run did, model call by model call and tool by tool, and the figures that the prompt cache,
-- the model routing and the grounding guardrail report for it.
--
-- chat_runs.input_tokens keeps meaning the UNCACHED input tokens of the run. Tokens that were read from or written to
-- the prompt cache have their own columns and are never added to it, so each count has one price.
ALTER TABLE chat_runs
    ADD COLUMN cache_read_tokens    INT         NOT NULL DEFAULT 0,
    ADD COLUMN cache_write_tokens   INT         NOT NULL DEFAULT 0,
    ADD COLUMN routed_reason        VARCHAR(24) NULL,
    ADD COLUMN guardrail_status     VARCHAR(24) NULL,
    ADD COLUMN guardrail_violations INT         NOT NULL DEFAULT 0;

COMMENT ON COLUMN chat_runs.input_tokens IS
    'Uncached input tokens only; tokens read from or written to the prompt cache are in cache_read_tokens and cache_write_tokens';

CREATE TABLE run_llm_calls (
    run_id             UUID        NOT NULL REFERENCES chat_runs (id) ON DELETE CASCADE,
    seq                INT         NOT NULL,
    model              VARCHAR(64) NOT NULL,
    input_tokens       INT         NOT NULL,
    output_tokens      INT         NOT NULL,
    cache_read_tokens  INT         NOT NULL DEFAULT 0,
    cache_write_tokens INT         NOT NULL DEFAULT 0,
    latency_ms         BIGINT      NOT NULL,
    stop_reason        VARCHAR(24) NULL,
    outcome            VARCHAR(16) NOT NULL CHECK (outcome IN ('OK', 'ERROR')),
    error_code         VARCHAR(32) NULL,
    started_at         TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (run_id, seq)
);

CREATE TABLE run_tool_calls (
    run_id     UUID        NOT NULL REFERENCES chat_runs (id) ON DELETE CASCADE,
    seq        INT         NOT NULL,
    tool_name  VARCHAR(64) NOT NULL,
    args       TEXT        NOT NULL,
    summary    TEXT        NOT NULL,
    error      BOOLEAN     NOT NULL,
    latency_ms BIGINT      NOT NULL,
    PRIMARY KEY (run_id, seq)
);
