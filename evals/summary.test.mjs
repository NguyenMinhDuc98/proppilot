// node --test evals/summary.test.mjs
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { summarize, summarizeByModel } from './summary.mjs'

const result = (model, pass, costUsd, latencyMs) => ({ model, pass, costUsd, latencyMs })

test('a summary holds the pass rate and the averages', () => {
  const summary = summarize([result('a', true, 0.001, 100), result('a', false, 0.003, 300)])

  assert.deepEqual(summary, { total: 2, passed: 1, passRate: 0.5, avgCostUsd: 0.002, avgLatencyMs: 200 })
})

test('no results sum up to zeros', () => {
  assert.deepEqual(summarize([]), { total: 0, passed: 0, passRate: 0, avgCostUsd: 0, avgLatencyMs: 0 })
})

test('each model that answered has its own averages', () => {
  const byModel = summarizeByModel([
    result('claude-haiku-4-5-20251001', true, 0.001, 1000),
    result('claude-sonnet-4-5', true, 0.01, 5000),
    result('claude-haiku-4-5-20251001', false, 0.003, 3000),
  ])

  assert.deepEqual(Object.keys(byModel), ['claude-haiku-4-5-20251001', 'claude-sonnet-4-5'])
  assert.equal(byModel['claude-haiku-4-5-20251001'].total, 2)
  assert.equal(byModel['claude-haiku-4-5-20251001'].avgCostUsd, 0.002)
  assert.equal(byModel['claude-sonnet-4-5'].total, 1)
  assert.equal(byModel['claude-sonnet-4-5'].avgLatencyMs, 5000)
})

test('a run that failed before a model answered belongs to no model', () => {
  const byModel = summarizeByModel([result(undefined, false, 0, 0), result('rule-based', true, 0, 12)])

  assert.deepEqual(Object.keys(byModel), ['rule-based'])
})
