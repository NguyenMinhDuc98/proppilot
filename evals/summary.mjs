// How the results of a run are summed up. Kept apart from run.mjs so it can be tested without a backend
// (summary.test.mjs).

const avg = (xs) => (xs.length ? xs.reduce((a, b) => a + b, 0) / xs.length : 0)

/** Pass rate, average cost and average latency of some results. */
export function summarize(rows) {
  const passed = rows.filter((r) => r.pass).length
  return {
    total: rows.length,
    passed,
    passRate: rows.length ? passed / rows.length : 0,
    avgCostUsd: avg(rows.map((r) => r.costUsd)),
    avgLatencyMs: avg(rows.map((r) => r.latencyMs)),
  }
}

/**
 * The summary of each model that answered, by model name. With a larger model configured the questions of one run are
 * answered by different models at different prices, so one model name over the averages of all of them would be wrong.
 * A result without a model (the request failed) belongs to none.
 */
export function summarizeByModel(results) {
  const byModel = new Map()
  for (const result of results) {
    if (result.model) byModel.set(result.model, [...(byModel.get(result.model) ?? []), result])
  }
  return Object.fromEntries([...byModel].map(([model, rows]) => [model, summarize(rows)]))
}

/**
 * How many answers each guardrail status describes: OK, FLAGGED (values the data does not support), RETRIED_OK and
 * RETRIED_FLAGGED (the model was asked again). A result without a status, from a request that failed or a server whose
 * guardrail is off, counts for none.
 */
export function summarizeGuardrail(results) {
  const counts = {}
  for (const { guardrailStatus } of results) {
    if (guardrailStatus) counts[guardrailStatus] = (counts[guardrailStatus] ?? 0) + 1
  }
  return counts
}
