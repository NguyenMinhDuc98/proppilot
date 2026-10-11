// How the events of one chat reply add up to what a person was shown. Kept apart from run.mjs so it can be tested
// without a backend (stream.test.mjs).

/** What a reply has delivered so far. */
export function newRun() {
  return { answer: '', tools: [], done: null, error: null, unverified: [] }
}

/**
 * Adds one server-sent event to the run.
 *
 * `revise` is sent when the grounding guardrail (GUARDRAIL_MODE=retry) asked the model to answer again: what was
 * streamed so far was dropped by the client, so it is not the answer and is not graded. It may come again when the new
 * answer never completed, followed by the earlier answer.
 */
export function apply(run, event, data) {
  if (event === 'token') run.answer += data.text
  else if (event === 'revise') run.answer = ''
  else if (event === 'tool_call') run.tools.push(data.name)
  else if (event === 'guardrail') run.unverified = data.values
  else if (event === 'done') run.done = data
  else if (event === 'error') run.error = data.message
  return run
}
