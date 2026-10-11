-- The model call that asked for each tool: run_llm_calls.seq of the call whose reply requested it, so that a stored
-- trace can be read as the model call, then the tools it asked for, then the next model call. Null for a row stored
-- before this column existed, which a trace lists after the model calls.
ALTER TABLE run_tool_calls
    ADD COLUMN llm_seq INT NULL;
