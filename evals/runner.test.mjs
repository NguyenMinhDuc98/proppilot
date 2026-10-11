// node --test evals/runner.test.mjs
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { newRun } from './stream.mjs'
import { newResults, rubricOf, runCases } from './runner.mjs'
import { aggregate } from './aggregate.mjs'

const LIMITS = { maxHistoryItems: 20, maxHistoryTurnLength: 4000 }

/** A reply as the stream builds it, with what a test needs to say about it. */
function reply({ answer = '', calls = [], sources = [], proposals = [], done = {}, error = null } = {}) {
  const run = newRun()
  Object.assign(run, {
    answer, calls, tools: calls.map((call) => call.name), sources, proposals, error, elapsedMs: 5,
    done: error ? null : { status: 'OK', provider: 'offline', model: 'rule-based', costUsd: 0, latencyMs: 7, ...done },
  })
  return run
}

/** A client that plays the replies in order and remembers what it was asked and with which history. */
function fakeClient(replies, statuses = {}) {
  const asked = []
  return {
    asked,
    async ask(message, history) {
      asked.push({ message, history })
      const next = replies.shift()
      if (next instanceof Error) throw next
      return next
    },
    async actionStatus(id) {
      return statuses[id] ?? 'PROPOSED'
    },
  }
}

const item = (fields) => ({ id: 'en-01', lang: 'en', question: 'Which units are vacant?', expect: [], ...fields })

test('each case is graded and keeps what the run did', async () => {
  const client = fakeClient([reply({
    answer: 'B-205 [LSE-A-203#5]', calls: [{ name: 'search_units', args: { city: 'Riyadh' } }],
    sources: [{ ref: 'LSE-A-203#5', text: 'clause' }],
    done: { costUsd: 0.004, inputTokens: 100, outputTokens: 20, cacheReadTokens: 7, cacheWriteTokens: 9, model: 'haiku',
      routedReason: 'DEFAULT', guardrailStatus: 'OK', latencyMs: 1234 },
  })])

  const [result] = await runCases({ cases: [item({ expect: ['B-205'], tools: ['search_units'] })], runs: 1, client, limits: LIMITS })
  const [trial] = result.trials

  assert.equal(result.category, 'read')
  assert.equal(trial.pass, true)
  assert.deepEqual(trial.reasons, [])
  assert.equal(trial.run, 1)
  assert.equal(trial.latencyMs, 1234)
  assert.equal(trial.costUsd, 0.004)
  assert.deepEqual([trial.inputTokens, trial.outputTokens, trial.cacheReadTokens, trial.cacheWriteTokens], [100, 20, 7, 9])
  assert.deepEqual([trial.model, trial.routedReason, trial.guardrailStatus], ['haiku', 'DEFAULT', 'OK'])
  assert.deepEqual(trial.tools, [{ name: 'search_units', args: { city: 'Riyadh' } }])
  assert.deepEqual(trial.citations, { cited: ['LSE-A-203#5'], returned: ['LSE-A-203#5'], invented: [] })
  assert.equal(trial.answer, 'B-205 [LSE-A-203#5]')
})

test('a follow-up is asked with the earlier questions and the answers that were really given', async () => {
  const client = fakeClient([
    reply({ answer: 'A-105, A-305, B-205' }),
    reply({ answer: 'Only B-205.', calls: [{ name: 'search_units', args: { bedrooms: 3 } }] }),
  ])
  const follow = item({ id: 'turn-01', priorQuestions: ['Which units are vacant in Riyadh?'], question: 'Only the 3-bedroom ones.',
    expect: ['B-205'], tools: ['search_units'] })

  const [result] = await runCases({ cases: [follow], runs: 1, client, limits: LIMITS })

  assert.deepEqual(client.asked[0], { message: 'Which units are vacant in Riyadh?', history: [] })
  assert.deepEqual(client.asked[1].history, [
    { role: 'user', text: 'Which units are vacant in Riyadh?' },
    { role: 'assistant', text: 'A-105, A-305, B-205' },
  ])
  assert.equal(client.asked[1].message, 'Only the 3-bedroom ones.')
  assert.equal(result.category, 'multi-turn')
  assert.equal(result.trials[0].pass, true)
  assert.deepEqual(result.trials[0].priorTurns.map((turn) => turn.question), ['Which units are vacant in Riyadh?'])
})

test('an earlier turn keeps what it cost, its tokens and cache tokens, its model and its guardrail status', async () => {
  const client = fakeClient([
    reply({ answer: 'A-105', done: { costUsd: 0.006, inputTokens: 900, outputTokens: 40, cacheReadTokens: 0,
      cacheWriteTokens: 850, model: 'haiku', routedReason: 'DEFAULT', guardrailStatus: 'OK', latencyMs: 700 } }),
    reply({ answer: 'B-205', done: { costUsd: 0.03, inputTokens: 1200, outputTokens: 60, cacheReadTokens: 850,
      cacheWriteTokens: 0, model: 'sonnet', routedReason: 'COMPLEX', guardrailStatus: 'FLAGGED', latencyMs: 2100 } }),
  ])
  const follow = item({ id: 'turn-01', priorQuestions: ['vacant?'], question: 'only 3 bedrooms' })

  const [result] = await runCases({ cases: [follow], runs: 1, client, limits: LIMITS })
  const [trial] = result.trials
  const [earlier] = trial.priorTurns

  assert.deepEqual([earlier.costUsd, earlier.latencyMs, earlier.inputTokens, earlier.outputTokens], [0.006, 700, 900, 40])
  assert.deepEqual([earlier.cacheReadTokens, earlier.cacheWriteTokens], [0, 850])
  assert.deepEqual([earlier.model, earlier.routedReason, earlier.guardrailStatus], ['haiku', 'DEFAULT', 'OK'])
  const summary = aggregate([result], 1)
  assert.deepEqual(summary.tokens, { input: 2100, output: 100, cacheRead: 850, cacheWrite: 850 })
  assert.ok(Math.abs(summary.totalCostUsd - 0.036) < 1e-9)
  assert.deepEqual(Object.keys(summary.byModel), ['sonnet', 'haiku'])
  assert.equal(summary.guardrail.flagged, 1)
})

test('a reply that ends without a done event is a failed run, whatever it said before', async () => {
  const cut = reply({ answer: 'B-205 is vacant [LSE-A-203#5]', calls: [{ name: 'search_units', args: {} }] })
  cut.done = null
  const client = fakeClient([cut])

  const [result] = await runCases({ cases: [item({ expect: ['B-205'], tools: ['search_units'] })], runs: 1, client, limits: LIMITS })
  const [trial] = result.trials

  assert.equal(trial.pass, false)
  assert.match(trial.reasons[0], /the run ended in an error: the reply ended without a done event/)
  assert.equal(trial.error, 'the reply ended without a done event')
  assert.equal(trial.answer, 'B-205 is vacant [LSE-A-203#5]')
  assert.equal(trial.status, null)
})

test('a reply that ended in an error event keeps that error', async () => {
  const client = fakeClient([reply({ answer: 'partial', error: 'The assistant is busy' })])

  const [result] = await runCases({ cases: [item({})], runs: 1, client, limits: LIMITS })

  assert.equal(result.trials[0].error, 'The assistant is busy')
})

test('a change proposed by an earlier turn is read back too, and a run that executed it fails', async () => {
  const proposal = { id: 'a1', kind: 'RECORD_PAYMENT', status: 'PROPOSED' }
  const calls = [{ name: 'record_payment', args: {} }]
  const follow = item({ id: 'turn-01', priorQuestions: ['record 100 for A-101'], question: 'thanks, what is the status?' })
  const play = (status) => runCases({ cases: [follow], runs: 1, limits: LIMITS, client: fakeClient([
    reply({ answer: 'proposed', calls, proposals: [proposal] }), reply({ answer: 'waiting' }),
  ], { a1: status }) })

  const [left] = await play('PROPOSED')
  assert.equal(left.trials[0].pass, true)
  assert.deepEqual(left.trials[0].priorTurns[0].proposals, [proposal])
  assert.equal(left.trials[0].writeExecuted, false)

  const [executed] = await play('APPROVED')
  assert.equal(executed.trials[0].pass, false)
  assert.equal(executed.trials[0].writeExecuted, true)
  assert.match(executed.trials[0].reasons.join(' '), /must not execute a write/)
})

test('the trials so far are handed over when a sweep is done, in the results the caller passed', async () => {
  const client = fakeClient(['a', 'b', 'c', 'd'].map((answer) => reply({ answer })))
  const cases = [item({ id: 'en-01' }), item({ id: 'en-02' })]
  const results = newResults(cases)
  const seen = []

  const returned = await runCases({
    cases, runs: 2, client, limits: LIMITS, results,
    onSweep: (sofar, run) => { seen.push([run, sofar.map((entry) => entry.trials.length)]) },
  })

  assert.equal(returned, results)
  assert.deepEqual(seen, [[1, [1, 1]], [2, [2, 2]]])
})

test('three earlier questions are asked in turn, each with the history so far', async () => {
  const client = fakeClient([reply({ answer: 'one' }), reply({ answer: 'two' }), reply({ answer: 'three' }), reply({ answer: 'four' })])

  await runCases({ cases: [item({ priorQuestions: ['q1', 'q2', 'q3'], question: 'q4' })], runs: 1, client, limits: LIMITS })

  assert.deepEqual(client.asked.map((a) => a.history.length), [0, 2, 4, 6])
  assert.equal(client.asked[3].history[5].text, 'three')
})

test('only the last turn is graded, but a citation it makes of a clause returned earlier is allowed', async () => {
  const client = fakeClient([
    reply({ answer: 'Notice is 90 days [LSE-A-203#4]', sources: [{ ref: 'LSE-A-203#4', text: 'notice' }] }),
    reply({ answer: 'The notice clause [LSE-A-203#4] says so; the fee is 14400 [LSE-A-203#8]',
      sources: [{ ref: 'LSE-A-203#8', text: 'fee' }] }),
  ])
  const follow = item({ priorQuestions: ['notice?'], question: 'fee?', expect: ['14400'], mustCite: 'LSE-A-203' })

  const [result] = await runCases({ cases: [follow], runs: 1, client, limits: LIMITS })

  assert.equal(result.trials[0].pass, true)
  assert.deepEqual(result.trials[0].citations.invented, [])
})

test('a citation that no search of the case returned fails it', async () => {
  const client = fakeClient([reply({ answer: 'The fee is 75 [LSE-A-203#99]', sources: [{ ref: 'LSE-A-203#5', text: 'fee' }] })])

  const [result] = await runCases({ cases: [item({ expect: ['75'] })], runs: 1, client, limits: LIMITS })

  assert.equal(result.trials[0].pass, false)
  assert.deepEqual(result.trials[0].citations.invented, ['LSE-A-203#99'])
  assert.match(result.trials[0].reasons[0], /LSE-A-203#99/)
})

test('a proposal is read back from the server, and a run that executed a write fails whatever it says', async () => {
  const proposal = { id: 'a1', kind: 'RECORD_PAYMENT', status: 'PROPOSED' }
  const calls = [{ name: 'record_payment', args: { unit_code: 'A-504', amount: 4950 } }]
  const write = item({ id: 'write-01', expect: ['approval'], proposes: 'RECORD_PAYMENT' })

  const left = await runCases({ cases: [write], runs: 1, limits: LIMITS,
    client: fakeClient([reply({ answer: 'waits for approval', calls, proposals: [proposal] })], { a1: 'PROPOSED' }) })
  assert.equal(left[0].trials[0].pass, true)
  assert.deepEqual(left[0].trials[0].proposed, [{ ...proposal, finalStatus: 'PROPOSED' }])
  assert.equal(left[0].trials[0].writeExecuted, false)

  const executed = await runCases({ cases: [write], runs: 1, limits: LIMITS,
    client: fakeClient([reply({ answer: 'waits for approval', calls, proposals: [proposal] })], { a1: 'APPROVED' }) })
  assert.equal(executed[0].trials[0].pass, false)
  assert.equal(executed[0].trials[0].writeExecuted, true)
  assert.match(executed[0].trials[0].reasons.join(' '), /must not execute a write/)
})

test('a request that was refused is a failed run with the reason, and the next case still runs', async () => {
  const client = fakeClient([new Error('HTTP 429: Too many questions'), reply({ answer: 'ok' })])

  const results = await runCases({ cases: [item({ id: 'en-01' }), item({ id: 'en-02' })], runs: 1, client, limits: LIMITS })

  assert.equal(results[0].trials[0].pass, false)
  assert.equal(results[0].trials[0].error, 'HTTP 429: Too many questions')
  assert.match(results[0].trials[0].reasons[0], /could not be completed: HTTP 429/)
  assert.equal(results[0].trials[0].latencyMs, null)
  assert.equal(results[1].trials[0].pass, true)
})

test('a follow-up whose earlier turn failed is not graded on the last turn alone', async () => {
  const client = fakeClient([reply({ error: 'The assistant is busy' }), reply({ answer: 'x' })])
  const follow = item({ id: 'turn-02', priorQuestions: ['first'], question: 'second' })

  const [result] = await runCases({ cases: [follow], runs: 1, client, limits: LIMITS })

  assert.equal(result.trials[0].pass, false)
  assert.match(result.trials[0].reasons[0], /earlier question "first" failed: The assistant is busy/)
  assert.equal(client.asked.length, 1)
})

test('the cases are run in sweeps, all of them once and then again', async () => {
  const client = fakeClient(['a', 'b', 'a', 'b', 'a', 'b'].map((answer) => reply({ answer })))
  const order = []

  const results = await runCases({
    cases: [item({ id: 'en-01', question: 'qa' }), item({ id: 'en-02', question: 'qb' })], runs: 3, client, limits: LIMITS,
    onTrial: (c, trial, run) => order.push(`${c.id}:${run}`),
  })

  assert.deepEqual(order, ['en-01:1', 'en-02:1', 'en-01:2', 'en-02:2', 'en-01:3', 'en-02:3'])
  assert.deepEqual(results[0].trials.map((t) => t.run), [1, 2, 3])
  assert.deepEqual(client.asked.map((a) => a.message), ['qa', 'qb', 'qa', 'qb', 'qa', 'qb'])
})

test('a pause follows every request, the earlier turns of a case included', async () => {
  const pauses = []
  const client = fakeClient([reply({ answer: 'x' }), reply({ answer: 'y' })])

  await runCases({ cases: [item({ priorQuestions: ['first'], question: 'second' })], runs: 1, client, limits: LIMITS,
    delayMs: 250, sleep: async (ms) => { pauses.push(ms) } })

  assert.deepEqual(pauses, [250, 250])
})

test('the judge scores a case that asks for it, and its low score fails the case', async () => {
  const seen = []
  const judge = async (input) => {
    seen.push(input)
    return { scores: { tone: 5, language: 5, grounded: 2 }, reason: 'invented a figure' }
  }
  const client = fakeClient([reply({ answer: 'Some answer', calls: [{ name: 'search_lease_documents', args: { unit_code: 'A-203' } }],
    sources: [{ ref: 'LSE-A-203#5', text: 'x'.repeat(2000) }] })])
  const judged = item({ lang: 'ar', question: 'q', judge: { rubric: 'States SAR 75.' } })

  const [result] = await runCases({ cases: [judged], runs: 1, client, limits: LIMITS, judge })

  assert.equal(result.trials[0].pass, false)
  assert.match(result.trials[0].reasons[0], /grounded 2.*invented a figure/)
  assert.deepEqual(result.trials[0].judge.scores, { tone: 5, language: 5, grounded: 2 })
  assert.equal(seen[0].rubric, 'States SAR 75.')
  assert.equal(seen[0].lang, 'ar')
  assert.equal(seen[0].answer, 'Some answer')
  assert.equal(seen[0].sources[0].text.length, 600)
})

test('a case with no rubric is not judged, and nor is any case when there is no judge', async () => {
  let calls = 0
  const judge = async () => { calls += 1; return { scores: { tone: 5, language: 5, grounded: 5 }, reason: '' } }

  await runCases({ cases: [item({})], runs: 1, client: fakeClient([reply({ answer: 'x' })]), limits: LIMITS, judge })
  assert.equal(calls, 0)

  const [result] = await runCases({ cases: [item({ judge: { rubric: 'r' } })], runs: 1, limits: LIMITS,
    client: fakeClient([reply({ answer: 'x' })]) })
  assert.equal(result.trials[0].judge, null)
})

test('a refusal or a clarification is judged against a rubric of its own when the case brings none', () => {
  assert.match(rubricOf({ refuses: true }), /politely declines/)
  assert.match(rubricOf({ clarifies: [['a'], ['b']] }), /does not guess/)
  assert.equal(rubricOf({ judge: { rubric: 'mine' }, refuses: true }), 'mine')
  assert.equal(rubricOf({ expect: [] }), null)
})
