-- Runs can now end as TRUNCATED (the model hit its token limit) or ABORTED (the client disconnected mid-stream).
ALTER TABLE chat_runs DROP CONSTRAINT chat_runs_status_check;
ALTER TABLE chat_runs ADD CONSTRAINT chat_runs_status_check
    CHECK (status IN ('OK', 'ERROR', 'MAX_ITERATIONS', 'TRUNCATED', 'ABORTED'));
