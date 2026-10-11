// How the events of one chat reply add up to what a person was shown. Kept apart from run.mjs so it can be tested
// without a backend (stream.test.mjs).

/** What a reply has delivered so far. */
export function newRun() {
  return {
    /** The text the person is left with: what the assistant said after its last tool call, as the web app shows it. */
    answer: '',
    /** What it said before a tool call; the web app keeps it apart from the answer. */
    preamble: '',
    /** The names of the tools called, in order. */
    tools: [],
    /** The same calls with the arguments the model chose. */
    calls: [],
    /** What each tool answered, as the stream says it: name, summary and whether it failed. */
    results: [],
    /** The clauses that lease searches returned, which are the only citations the answer may carry. */
    sources: [],
    /** The changes the run proposed: id, kind and the status the stream announced. */
    proposals: [],
    done: null,
    error: null,
    errorCode: null,
    unverified: [],
  }
}

/**
 * Adds one server-sent event to the run.
 *
 * `revise` is sent when the grounding guardrail (GUARDRAIL_MODE=retry) asked the model to answer again: what was
 * streamed so far was dropped by the client, so it is not the answer and is not graded. It may come again when the new
 * answer never completed, followed by the earlier answer.
 *
 * A tool call closes the text before it: that is a note on what the assistant is about to do, kept as the preamble.
 */
export function apply(run, event, data) {
  if (event === 'token') {
    run.answer += data.text
  } else if (event === 'revise') {
    run.answer = ''
  } else if (event === 'tool_call') {
    if (run.answer.trim()) run.preamble += (run.preamble ? '\n\n' : '') + run.answer.trim()
    run.answer = ''
    run.tools.push(data.name)
    run.calls.push({ name: data.name, args: data.args ?? {} })
  } else if (event === 'tool_result') {
    run.results.push({ name: data.name, summary: data.summary ?? '', error: Boolean(data.error) })
    run.sources.push(...(data.sources ?? []))
  } else if (event === 'action_proposed') {
    run.proposals.push({ id: data.id, kind: data.kind, status: data.status })
  } else if (event === 'guardrail') {
    run.unverified = data.values
  } else if (event === 'done') {
    run.done = data
  } else if (event === 'error') {
    run.error = data.message
    run.errorCode = data.code ?? null
  }
  return run
}
