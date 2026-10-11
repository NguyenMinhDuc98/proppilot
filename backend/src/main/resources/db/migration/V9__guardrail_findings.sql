-- What the grounding guardrail could not ground in the answer of a run: the kind of each claim and the claim, cut to 40
-- characters, for the admin's detail of the run. The status and the number of claims of a run are chat_runs.guardrail_status
-- and chat_runs.guardrail_violations (V7), which the public trace shows; the claims themselves are never public.
ALTER TABLE chat_runs ADD CONSTRAINT chat_runs_guardrail_status_check
    CHECK (guardrail_status IN ('OK', 'FLAGGED', 'RETRIED_OK', 'RETRIED_FLAGGED'));

CREATE TABLE run_guardrail_findings (
    run_id UUID        NOT NULL REFERENCES chat_runs (id) ON DELETE CASCADE,
    seq    INT         NOT NULL,
    kind   VARCHAR(16) NOT NULL CHECK (kind IN ('NUMBER', 'UNIT_CODE', 'TENANT_NAME', 'CITATION')),
    claim  VARCHAR(40) NOT NULL,
    PRIMARY KEY (run_id, seq)
);
