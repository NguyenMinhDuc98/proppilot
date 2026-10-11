// node --test evals/aggregate.test.mjs
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { aggregate, caseOutcome, percentile, standardDeviation } from './aggregate.mjs'

const trial = (pass, fields = {}) => ({ pass, latencyMs: 100, costUsd: 0, ...fields })
const item = (id, category, lang, ...trials) => ({ id, category, lang, trials })

test('the standard deviation is the sample one, and zero for one value', () => {
  assert.equal(standardDeviation([]), 0)
  assert.equal(standardDeviation([0.9]), 0)
  assert.equal(standardDeviation([0.5, 0.5, 0.5]), 0)
  assert.ok(Math.abs(standardDeviation([2, 4, 4, 4, 5, 5, 7, 9]) - 2.138) < 0.001)
  assert.ok(Math.abs(standardDeviation([0.9, 1.0]) - 0.0707) < 0.001)
})

test('the percentile is the nearest rank', () => {
  const values = Array.from({ length: 20 }, (_, i) => (i + 1) * 10)

  assert.equal(percentile(values, 95), 190)
  assert.equal(percentile(values, 50), 100)
  assert.equal(percentile(values, 100), 200)
  assert.equal(percentile([5], 95), 5)
  assert.equal(percentile([], 95), 0)
  assert.equal(percentile([30, 10, 20], 95), 30)
})

test('a case is flaky when it passed some of its runs and failed others', () => {
  assert.deepEqual(caseOutcome([trial(true), trial(true), trial(true)]), { passes: 3, runs: 3, flaky: false })
  assert.deepEqual(caseOutcome([trial(true), trial(false), trial(true)]), { passes: 2, runs: 3, flaky: true })
  assert.deepEqual(caseOutcome([trial(false), trial(false)]), { passes: 0, runs: 2, flaky: false })
  assert.deepEqual(caseOutcome([trial(true)]), { passes: 1, runs: 1, flaky: false })
})

test('the pass rate is the mean of the runs, and the deviation is how much the runs differed', () => {
  const cases = [
    item('a', 'read', 'en', trial(true), trial(true), trial(true)),
    item('b', 'read', 'en', trial(true), trial(false), trial(true)),
    item('c', 'lease', 'ar', trial(true), trial(true), trial(false)),
    item('d', 'lease', 'ar', trial(false), trial(false), trial(false)),
  ]

  const summary = aggregate(cases, 3)

  assert.deepEqual(summary.passRatePerRun, [0.75, 0.5, 0.5])
  assert.ok(Math.abs(summary.passRate - 0.5833) < 0.001)
  assert.ok(Math.abs(summary.passRateStdDev - 0.1443) < 0.001)
  assert.equal(summary.casesPassingEveryRun, 1)
  assert.deepEqual(summary.flakyCases, ['b', 'c'])
  assert.deepEqual(summary.failingCases, ['d'])
  assert.equal(summary.passedTrials, 7)
  assert.equal(summary.trials, 12)
})

test('the pass rate is split by category and by language', () => {
  const cases = [
    item('en-01', 'read', 'en', trial(true), trial(true)),
    item('ar-01', 'read', 'ar', trial(true), trial(false)),
    item('rag-01', 'lease', 'en', trial(false), trial(false)),
  ]

  const { byCategory, byLanguage } = aggregate(cases, 2)

  assert.deepEqual(byCategory.read, { cases: 2, trials: 4, passed: 3, passRate: 0.75 })
  assert.deepEqual(byCategory.lease, { cases: 1, trials: 2, passed: 0, passRate: 0 })
  assert.deepEqual(byLanguage.en, { cases: 2, trials: 4, passed: 2, passRate: 0.5 })
  assert.deepEqual(byLanguage.ar, { cases: 1, trials: 2, passed: 1, passRate: 0.5 })
})

test('latency is averaged over the runs that answered, with its p95', () => {
  const trials = Array.from({ length: 20 }, (_, i) => trial(true, { latencyMs: (i + 1) * 100 }))

  const summary = aggregate([item('a', 'read', 'en', ...trials, trial(false, { latencyMs: null }))], 21)

  assert.equal(summary.avgLatencyMs, 1050)
  assert.equal(summary.p95LatencyMs, 1900)
})

test('cost, tokens, cache tokens, guardrail flags and routing count every question asked, the earlier turns included', () => {
  const earlier = (fields) => ({ latencyMs: 50, costUsd: 0, inputTokens: 0, outputTokens: 0, cacheReadTokens: 0,
    cacheWriteTokens: 0, model: 'haiku', routedReason: 'DEFAULT', guardrailStatus: 'OK', ...fields })
  const trials = [
    trial(true, { costUsd: 0.004, inputTokens: 100, outputTokens: 20, cacheReadTokens: 0, cacheWriteTokens: 900,
      model: 'haiku', routedReason: 'DEFAULT', guardrailStatus: 'OK',
      priorTurns: [earlier({ costUsd: 0.001, inputTokens: 30, outputTokens: 5 })] }),
    trial(true, { costUsd: 0.002, inputTokens: 50, outputTokens: 10, cacheReadTokens: 900, cacheWriteTokens: 0,
      model: 'haiku', routedReason: 'DEFAULT', guardrailStatus: 'FLAGGED' }),
    trial(false, { costUsd: 0.03, inputTokens: 400, outputTokens: 80, model: 'sonnet', routedReason: 'COMPLEX',
      guardrailStatus: 'RETRIED_FLAGGED',
      priorTurns: [earlier({ costUsd: 0.01, inputTokens: 200, outputTokens: 40, latencyMs: 300 })] }),
  ]

  const summary = aggregate([item('a', 'read', 'en', ...trials)], 3)

  assert.equal(summary.trials, 3)
  assert.equal(summary.requests, 5)
  assert.ok(Math.abs(summary.avgCostUsd - 0.0094) < 1e-9)
  assert.ok(Math.abs(summary.totalCostUsd - 0.047) < 1e-9)
  assert.deepEqual(summary.tokens, { input: 780, output: 155, cacheRead: 900, cacheWrite: 900 })
  assert.deepEqual(summary.guardrail, { flagged: 2, byStatus: { OK: 3, FLAGGED: 1, RETRIED_FLAGGED: 1 } })
  assert.deepEqual(summary.routedReasons, { DEFAULT: 4, COMPLEX: 1 })
})

test('each model that answered has its own questions, pass rate, cost and latency', () => {
  const earlier = (fields) => ({ latencyMs: 50, costUsd: 0.001, model: 'haiku', ...fields })
  const trials = [
    trial(true, { costUsd: 0.004, latencyMs: 100, model: 'haiku', priorTurns: [earlier({})] }),
    trial(true, { costUsd: 0.002, latencyMs: 100, model: 'haiku' }),
    trial(false, { costUsd: 0.03, latencyMs: 4000, model: 'sonnet', priorTurns: [earlier({ costUsd: 0.01, latencyMs: 300 })] }),
    trial(true, { costUsd: 0.02, latencyMs: 2000, model: 'sonnet' }),
  ]

  const { byModel } = aggregate([item('a', 'read', 'en', ...trials)], 4)

  assert.deepEqual(Object.keys(byModel), ['haiku', 'sonnet'])
  assert.equal(byModel.haiku.requests, 4)
  assert.equal(byModel.haiku.trials, 2)
  assert.equal(byModel.haiku.passed, 2)
  assert.equal(byModel.haiku.passRate, 1)
  assert.ok(Math.abs(byModel.haiku.avgCostUsd - 0.00425) < 1e-9)
  assert.equal(byModel.haiku.avgLatencyMs, 137.5)
  assert.equal(byModel.haiku.p95LatencyMs, 300)
  assert.equal(byModel.sonnet.requests, 2)
  assert.equal(byModel.sonnet.trials, 2)
  assert.equal(byModel.sonnet.passRate, 0.5)
  assert.ok(Math.abs(byModel.sonnet.avgCostUsd - 0.025) < 1e-9)
  assert.equal(byModel.sonnet.avgLatencyMs, 3000)
  assert.equal(byModel.sonnet.p95LatencyMs, 4000)
})

test('a model that only answered earlier turns has no graded runs and a pass rate of zero', () => {
  const trials = [trial(true, { model: 'haiku', priorTurns: [{ latencyMs: 80, costUsd: 0.01, model: 'sonnet' }] })]

  const { byModel } = aggregate([item('a', 'read', 'en', ...trials)], 1)

  assert.deepEqual([byModel.sonnet.requests, byModel.sonnet.trials, byModel.sonnet.passRate], [1, 0, 0])
})

test('a run that failed before a model answered belongs to no model and has no status', () => {
  const summary = aggregate([item('a', 'read', 'en', trial(false, { latencyMs: null, model: null, guardrailStatus: null }))], 1)

  assert.deepEqual(summary.byModel, {})
  assert.deepEqual(summary.guardrail, { flagged: 0, byStatus: {} })
  assert.equal(summary.requests, 0)
  assert.equal(summary.avgLatencyMs, 0)
  assert.equal(summary.avgCostUsd, 0)
})

test('the earlier turns of a conversation that then failed are still counted', () => {
  const failed = trial(false, { latencyMs: null, costUsd: 0, priorTurns: [{ latencyMs: 80, costUsd: 0.01, inputTokens: 90 }] })

  const summary = aggregate([item('a', 'turn', 'en', failed)], 1)

  assert.equal(summary.requests, 1)
  assert.equal(summary.totalCostUsd, 0.01)
  assert.equal(summary.tokens.input, 90)
})

test('a stopped run is summed up over what ran: a sweep is rated on the cases that reached it', () => {
  const cases = [
    item('a', 'read', 'en', trial(true), trial(true)),
    item('b', 'read', 'en', trial(false), trial(false)),
    item('c', 'read', 'en', trial(true)),
  ]

  const summary = aggregate(cases, 2)

  assert.deepEqual(summary.passRatePerRun, [2 / 3, 0.5])
  assert.equal(summary.trials, 5)
  assert.equal(summary.casesPassingEveryRun, 2)
  assert.deepEqual(summary.failingCases, ['b'])
})

test('writes that a run executed are counted', () => {
  const summary = aggregate([item('a', 'write', 'en', trial(false, { writeExecuted: true }), trial(true))], 2)

  assert.equal(summary.writesExecuted, 1)
})

test('the judge scores are averaged, and the answers it could not judge are counted', () => {
  const judged = (tone, language, grounded) => trial(true, { judge: { scores: { tone, language, grounded } } })
  const trials = [judged(5, 5, 4), judged(3, 5, 4), trial(true, { judge: { error: 'x' } }), trial(true)]

  const { judge } = aggregate([item('a', 'read', 'en', ...trials)], 4)

  assert.deepEqual(judge, { judged: 2, errors: 1, averages: { tone: 4, language: 5, grounded: 4 } })
  assert.deepEqual(aggregate([item('a', 'read', 'en', trial(true))], 1).judge, { judged: 0, errors: 0, averages: null })
})

test('no cases sum up to zeros', () => {
  const summary = aggregate([], 3)

  assert.equal(summary.passRate, 0)
  assert.equal(summary.cases, 0)
  assert.equal(summary.avgLatencyMs, 0)
})
