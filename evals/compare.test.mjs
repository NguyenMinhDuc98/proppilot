// node --test evals/compare.test.mjs
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { compareResults, describeComparison } from './compare.mjs'
import { readPrevious } from './results.mjs'

const outcome = (id, passes, runs) => ({ id, passes, runs })
const document = (cases, aggregate = {}) => ({
  ranAt: '2026-10-11T00:00:00.000Z',
  cases,
  aggregate: {
    passRate: 0.5, avgLatencyMs: 100, avgCostUsd: 0.001, byCategory: { read: { passRate: 0.5 }, lease: { passRate: 1 } },
    ...aggregate,
  },
})

test('the pass rates, the latency and the cost are compared', () => {
  const previous = document([outcome('a', 1, 1)])
  const current = document([outcome('a', 1, 1)], { passRate: 0.75, avgLatencyMs: 80, avgCostUsd: 0.002 })

  const comparison = compareResults(current, previous)

  assert.deepEqual(comparison.passRate, { current: 0.75, previous: 0.5, delta: 0.25 })
  assert.equal(comparison.avgLatencyMs.delta, -20)
  assert.ok(Math.abs(comparison.avgCostUsd.delta - 0.001) < 1e-12)
})

test('a case is worse or better by its pass rate, whatever the number of runs', () => {
  const previous = document([outcome('a', 3, 3), outcome('b', 0, 3), outcome('c', 1, 1), outcome('gone', 1, 1)])
  const current = document([outcome('a', 2, 3), outcome('b', 1, 1), outcome('c', 1, 1), outcome('new', 1, 1)])

  const comparison = compareResults(current, previous)

  assert.deepEqual(comparison.regressed, [{ id: 'a', previous: '3/3', current: '2/3' }])
  assert.deepEqual(comparison.fixed, [{ id: 'b', previous: '0/3', current: '1/1' }])
  assert.deepEqual(comparison.added, ['new'])
  assert.deepEqual(comparison.removed, ['gone'])
})

test('only the categories both runs have are compared', () => {
  const previous = document([], { byCategory: { read: { passRate: 1 } } })
  const current = document([], { byCategory: { read: { passRate: 0.5 }, injection: { passRate: 1 } } })

  assert.deepEqual(compareResults(current, previous).byCategory, { read: { current: 0.5, previous: 1, delta: -0.5 } })
})

test('the comparison reads as lines of text', () => {
  const previous = document([outcome('a', 1, 1)], { passRate: 1 })
  const current = document([outcome('a', 0, 1)], { passRate: 0.5, byCategory: { read: { passRate: 0 }, lease: { passRate: 1 } } })

  const lines = describeComparison(compareResults(current, previous))

  assert.match(lines[0], /2026-10-11/)
  assert.ok(lines.some((line) => /pass rate 100\.0% -> 50\.0% \(-50\.0 pt\)/.test(line)))
  assert.ok(lines.some((line) => /read: 50\.0% -> 0\.0%/.test(line)))
  assert.ok(!lines.some((line) => /lease:/.test(line)))
  assert.ok(lines.some((line) => /worse: a \(1\/1 -> 0\/1\)/.test(line)))
})

test('a long list of cases that were not run is cut', () => {
  const ids = Array.from({ length: 12 }, (_, i) => `en-${i + 1}`)
  const previous = document(ids.map((id) => outcome(id, 1, 1)))
  const current = document([outcome('en-1', 1, 1)])

  const lines = describeComparison(compareResults(current, previous))

  assert.ok(lines.some((line) => /not in this run: en-2, en-3, en-4, en-5, en-6, en-7, en-8, en-9 and 3 more$/.test(line)))
})

test('the first results file, a flat list of results, is read as one run of each case', () => {
  const first = {
    ranAt: '2026-10-11T12:10:10.270Z',
    results: [
      { id: 'en-01', lang: 'en', pass: true, latencyMs: 100, costUsd: 0 },
      { id: 'rag-01', lang: 'en', pass: false, latencyMs: 300, costUsd: 0.002 },
    ],
  }

  const previous = readPrevious(first)

  assert.equal(previous.aggregate.passRate, 0.5)
  assert.equal(previous.aggregate.avgLatencyMs, 200)
  assert.deepEqual(previous.cases.map((c) => [c.id, c.passes, c.runs]), [['en-01', 1, 1], ['rag-01', 0, 1]])
  assert.deepEqual(Object.keys(previous.aggregate.byCategory), ['read', 'lease'])
})

test('a file that is not a results file is refused', () => {
  assert.throws(() => readPrevious({ hello: 'world' }), /not a results file/)
  assert.throws(() => readPrevious(null), /not a results file/)
})
