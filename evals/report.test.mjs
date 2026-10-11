// node --test evals/report.test.mjs
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { compareResults } from './compare.mjs'
import { buildResults } from './results.mjs'
import { renderSummary } from './report.mjs'

const trial = (pass, fields = {}) => ({
  pass, reasons: pass ? [] : ['missing "4800"'], latencyMs: 100, costUsd: 0, model: 'rule-based', provider: 'offline',
  guardrailStatus: 'OK', ...fields,
})
const item = (id, category, lang, ...trials) => ({ id, category, lang, question: id, trials })

function results({
  provider = 'offline', judge = { requested: false, enabled: false, model: null, reason: null }, cases, skipped = [],
  offlineOnly = true,
} = {}) {
  const info = { mode: provider, provider, model: 'rule-based', retrieval: 'keyword', leaseDocuments: 30 }
  return buildResults({
    ranAt: '2026-10-11T12:00:00.000Z', options: { runs: 3, only: [], offlineOnly, judge: judge.requested, delayMs: 0 },
    url: 'http://localhost:8080', info, judge, skipped,
    cases: cases ?? [
      item('en-01', 'read', 'en', trial(true), trial(true), trial(true)),
      item('ar-01', 'read', 'ar', trial(true), trial(false), trial(true)),
      item('rag-01', 'lease', 'en', trial(false), trial(false), trial(false)),
    ],
  })
}

test('the header says provider, models, runs, date, backend host and whether the judge ran', () => {
  const summary = renderSummary(results({ skipped: [{ id: 'turn-01' }, { id: 'turn-02' }] }))

  assert.match(summary, /\| Provider \| offline \|/)
  assert.match(summary, /\| Model\(s\) that answered \| rule-based \(9\) \|/)
  assert.match(summary, /\| Runs per case \| 3 \|/)
  assert.match(summary, /\| Cases \| 3, 2 skipped \(`offline: false`\): turn-01, turn-02 \|/)
  assert.match(summary, /\| Date \| 2026-10-11T12:00:00\.000Z \|/)
  assert.match(summary, /\| Backend \| localhost:8080, lease search by keyword \|/)
  assert.match(summary, /\| LLM judge \| not run \(`--judge` was not given\) \|/)
})

test('the judge line tells whether it was asked for, whether it could run and what it scored', () => {
  const missing = results({ judge: { requested: true, enabled: false, model: null, reason: 'ANTHROPIC_API_KEY is not set' } })
  assert.match(renderSummary(missing), /not run \(ANTHROPIC_API_KEY is not set\)/)

  const ran = results({
    provider: 'anthropic',
    judge: { requested: true, enabled: true, model: 'claude-haiku-4-5-20251001', reason: null },
    cases: [item('en-01', 'read', 'en', trial(true, { judge: { scores: { tone: 5, language: 5, grounded: 5 } } }),
      trial(true, { judge: { error: 'x' } }))],
  })
  assert.match(renderSummary(ran), /ran with claude-haiku-4-5-20251001: 1 answers scored, 1 could not be judged/)
})

test('an offline run says what it does not show, and a run of a real model does not', () => {
  assert.match(renderSummary(results()), /Offline run\./)
  assert.doesNotMatch(renderSummary(results({ provider: 'anthropic' })), /Offline run\./)
})

test('an offline run says that the cases for a real model were left out only when they were', () => {
  const left = renderSummary(results({ skipped: [{ id: 'turn-01' }] }))
  assert.match(left, /that only a real model can pass \(`offline: false`\) were left out/)

  const all = renderSummary(results({ offlineOnly: false }))
  assert.doesNotMatch(all, /were left out|were not run/)
  assert.match(all, /A case marked `offline: false` is one that only a real model is expected to pass/)
})

test('the overall table holds the pass rate, the deviation, the flaky cases, the latency and the cost', () => {
  const summary = renderSummary(results())

  assert.match(summary, /Pass rate \(mean over runs\) \| 55\.6% \(5\/9 runs of cases\)/)
  assert.match(summary, /Standard deviation of the pass rate across runs \| 19\.25 pt/)
  assert.match(summary, /Cases that passed every run \| 1\/3/)
  assert.match(summary, /Flaky cases \(passed some runs\) \| 1/)
  assert.match(summary, /Cases that failed every run \| 1/)
  assert.match(summary, /Questions asked \(earlier turns of follow-ups included\) \| 9/)
  assert.match(summary, /Average latency \| 100 ms/)
  assert.match(summary, /p95 latency \| 100 ms/)
  assert.match(summary, /Average cost per question \| \$0\.00000/)
  assert.match(summary, /Total cost \| \$0\.00000/)
})

test('the overall table counts the guardrail verdicts, so that an answer a retry made clean shows', () => {
  const retried = results({ cases: [item('en-01', 'read', 'en',
    trial(true), trial(true), trial(true, { guardrailStatus: 'RETRIED_FLAGGED' }),
    trial(true, { guardrailStatus: 'FLAGGED' }), trial(true, { guardrailStatus: 'RETRIED_OK' }),
    trial(true, { guardrailStatus: null }))] })

  const summary = renderSummary(retried)

  assert.match(summary, /\| Answers the guardrail flagged \| 2 \|/)
  assert.match(summary, /\| Guardrail verdicts \| OK 2, RETRIED_OK 1, FLAGGED 1, RETRIED_FLAGGED 1 \|/)
})

test('a run in which the guardrail checked nothing says so', () => {
  const unchecked = results({ cases: [item('en-01', 'read', 'en', trial(true, { guardrailStatus: null }))] })

  assert.match(renderSummary(unchecked), /\| Guardrail verdicts \| none \|/)
})

test('a run that more than one model answered has a table of what each of them did', () => {
  const mixed = results({
    provider: 'anthropic',
    cases: [item('en-01', 'read', 'en',
      trial(true, { model: 'haiku', costUsd: 0.004, latencyMs: 800 }),
      trial(false, { model: 'sonnet', costUsd: 0.03, latencyMs: 4200, routedReason: 'COMPLEX' }))],
  })

  const summary = renderSummary(mixed)

  assert.match(summary, /\| Model\(s\) that answered \| haiku \(1\), sonnet \(1\) \|/)
  assert.match(summary, /## By answering model/)
  assert.match(summary, /\| haiku \| 1 \| 1\/1 \| 100\.0% \| \$0\.00400 \| 800 ms \| 800 ms \|/)
  assert.match(summary, /\| sonnet \| 1 \| 0\/1 \| 0\.0% \| \$0\.03000 \| 4\.20 s \| 4\.20 s \|/)
  assert.doesNotMatch(renderSummary(results()), /By answering model/)
})

test('a run that was stopped says so, and how many runs of each case it reached', () => {
  const stopped = results({ cases: [
    item('en-01', 'read', 'en', trial(true), trial(true)),
    item('en-02', 'read', 'en', trial(true)),
  ] })

  const summary = renderSummary(stopped)

  assert.equal(stopped.complete, false)
  assert.match(summary, /Incomplete run\./)
  assert.match(summary, /The figures cover the 3 runs of cases that finished\./)
  assert.match(summary, /\| Runs per case \| 3 asked for, 2 reached \|/)
  assert.doesNotMatch(renderSummary(results()), /Incomplete run/)
  assert.equal(results().complete, true)
})

test('a run stopped before any case finished does not claim that every case passed', () => {
  const none = results({ cases: [item('en-01', 'read', 'en'), item('en-02', 'read', 'en')] })

  assert.match(renderSummary(none), /No case has finished a run\./)
  assert.doesNotMatch(renderSummary(none), /Every case passed/)
})

test('the pass rate is shown by category in a fixed order and by language', () => {
  const summary = renderSummary(results())

  assert.match(summary, /## By category[\s\S]*\| read \| 2 \| 5\/6 \| 83\.3% \|[\s\S]*\| lease \| 1 \| 0\/3 \| 0\.0% \|/)
  assert.match(summary, /## By language[\s\S]*\| English \| 2 \| 3\/6 \| 50\.0% \|[\s\S]*\| Arabic \| 1 \| 2\/3 \| 66\.7% \|/)
  assert.ok(summary.indexOf('| read |') < summary.indexOf('| lease |'))
})

test('a failing case and a flaky one are listed with their passes and their reasons', () => {
  const summary = renderSummary(results())

  assert.match(summary, /\| rag-01 \| lease \| en \| 0\/3 \| missing "4800" \|/)
  assert.match(summary, /\| ar-01 \| read \| ar \| 2\/3 \(flaky\) \| missing "4800" \|/)
  assert.doesNotMatch(summary, /\| en-01 \| read/)
})

test('a latency of a second or more is written in seconds', () => {
  const slow = results({ cases: [item('en-01', 'read', 'en', trial(true, { latencyMs: 4200 }), trial(true, { latencyMs: 5800 }))] })

  assert.match(renderSummary(slow), /Average latency \| 5\.00 s/)
})

test('when everything passed there is no table of failures', () => {
  const summary = renderSummary(results({ cases: [item('en-01', 'read', 'en', trial(true), trial(true), trial(true))] }))

  assert.match(summary, /Every case passed every run\./)
})

test('a reason with a pipe or a line break does not break the table', () => {
  const summary = renderSummary(results({
    cases: [item('en-01', 'read', 'en', trial(false, { reasons: ['unwanted "a|b"\nnext'] }))],
  }))

  assert.match(summary, /unwanted "a\\\|b" next/)
})

test('a comparison with an earlier run is part of the summary when one is given', () => {
  const current = results()
  const previous = results({ cases: [item('en-01', 'read', 'en', trial(true), trial(true), trial(true))] })

  const summary = renderSummary(current, compareResults(current, previous))

  assert.match(summary, /## Compared with an earlier run/)
  assert.match(summary, /pass rate 100\.0% -> 55\.6%/)
  assert.doesNotMatch(renderSummary(current), /Compared with an earlier run/)
})

test('the results file holds the aggregate and each case with its outcome', () => {
  const document = results()

  assert.equal(document.format, 2)
  assert.equal(document.backend.host, 'localhost:8080')
  assert.equal(document.aggregate.runs, 3)
  assert.deepEqual(document.cases.map((c) => [c.id, c.passes, c.runs, c.flaky]),
    [['en-01', 3, 3, false], ['ar-01', 2, 3, true], ['rag-01', 0, 3, false]])
})
