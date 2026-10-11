// How the runs of a whole eval are summed up: per case, per category and language, and overall. Kept apart from
// run.mjs so it can be tested without a backend (aggregate.test.mjs).

const mean = (values) => (values.length ? values.reduce((a, b) => a + b, 0) / values.length : 0)
const sum = (values) => values.reduce((a, b) => a + b, 0)
const count = (values) => values.reduce((counts, value) => ({ ...counts, [value]: (counts[value] ?? 0) + 1 }), {})

/**
 * The chat requests that got a reply: the graded one of each trial and the earlier turns of its conversation. Cost,
 * tokens, latency, models and guardrail statuses are all counted over these, so that they add up to one another; the
 * pass rate is counted over the graded turns.
 */
const answeredRequests = (trials) => trials
  .flatMap((trial) => [trial, ...(trial.priorTurns ?? [])])
  .filter((request) => typeof request.latencyMs === 'number')

/** The sample standard deviation (n - 1); 0 for fewer than two values. */
export function standardDeviation(values) {
  if (values.length < 2) return 0
  const centre = mean(values)
  return Math.sqrt(sum(values.map((value) => (value - centre) ** 2)) / (values.length - 1))
}

/** The nearest-rank percentile (p from 0 to 100); 0 for no values. */
export function percentile(values, p) {
  if (values.length === 0) return 0
  const sorted = [...values].sort((a, b) => a - b)
  return sorted[Math.max(0, Math.ceil((p / 100) * sorted.length) - 1)]
}

/** A case over its runs: how many passed, and whether it passed some and failed others. */
export function caseOutcome(trials) {
  const passes = trials.filter((trial) => trial.pass).length
  return { passes, runs: trials.length, flaky: passes > 0 && passes < trials.length }
}

function group(cases, key) {
  const groups = {}
  for (const item of cases) {
    const name = key(item)
    groups[name] ??= { cases: 0, trials: 0, passed: 0 }
    groups[name].cases += 1
    groups[name].trials += item.trials.length
    groups[name].passed += item.trials.filter((trial) => trial.pass).length
  }
  return Object.fromEntries(Object.entries(groups).map(([name, g]) => [
    name, { ...g, passRate: g.trials ? g.passed / g.trials : 0 },
  ]))
}

/** For each model that answered: how much it was asked, how it did on the graded turns, what it cost, how long it took. */
function modelSummaries(trials, requests) {
  const names = [...new Set(requests.map((request) => request.model).filter(Boolean))]
  return Object.fromEntries(names.map((name) => {
    const asked = requests.filter((request) => request.model === name)
    const graded = trials.filter((trial) => trial.model === name)
    const passed = graded.filter((trial) => trial.pass).length
    const latencies = asked.map((request) => request.latencyMs)
    return [name, {
      requests: asked.length,
      trials: graded.length,
      passed,
      passRate: graded.length ? passed / graded.length : 0,
      avgCostUsd: mean(asked.map((request) => request.costUsd ?? 0)),
      avgLatencyMs: mean(latencies),
      p95LatencyMs: percentile(latencies, 95),
    }]
  }))
}

function judgeSummary(trials) {
  const judged = trials.filter((trial) => trial.judge?.scores)
  const average = (name) => mean(judged.map((trial) => trial.judge.scores[name]))
  return {
    judged: judged.length,
    errors: trials.filter((trial) => trial.judge?.error).length,
    averages: judged.length
      ? { tone: average('tone'), language: average('language'), grounded: average('grounded') }
      : null,
  }
}

/**
 * @param {{id: string, category: string, lang: string, trials: object[]}[]} cases each with one trial per run; a run
 *   that was stopped has fewer, and its last sweep only the cases that got to run
 * @param {number} runs how many runs (sweeps) there are at least one trial of
 */
export function aggregate(cases, runs) {
  const trials = cases.flatMap((item) => item.trials)
  const requests = answeredRequests(trials)
  const perRun = Array.from({ length: runs }, (_, index) => {
    const ran = cases.filter((item) => item.trials[index])
    return ran.length ? ran.filter((item) => item.trials[index].pass).length / ran.length : 0
  })
  const outcomes = cases.map((item) => ({ id: item.id, ...caseOutcome(item.trials) }))
  const latencies = requests.map((request) => request.latencyMs)
  const statuses = requests.map((request) => request.guardrailStatus).filter(Boolean)
  return {
    cases: cases.length,
    runs,
    trials: trials.length,
    requests: requests.length,
    passedTrials: trials.filter((trial) => trial.pass).length,
    passRate: mean(perRun),
    passRateStdDev: standardDeviation(perRun),
    passRatePerRun: perRun,
    casesPassingEveryRun: outcomes.filter((o) => o.passes === o.runs).length,
    flakyCases: outcomes.filter((o) => o.flaky).map((o) => o.id),
    failingCases: outcomes.filter((o) => o.passes === 0).map((o) => o.id),
    avgLatencyMs: mean(latencies),
    p95LatencyMs: percentile(latencies, 95),
    avgCostUsd: mean(requests.map((request) => request.costUsd ?? 0)),
    totalCostUsd: sum(requests.map((request) => request.costUsd ?? 0)),
    tokens: {
      input: sum(requests.map((request) => request.inputTokens ?? 0)),
      output: sum(requests.map((request) => request.outputTokens ?? 0)),
      cacheRead: sum(requests.map((request) => request.cacheReadTokens ?? 0)),
      cacheWrite: sum(requests.map((request) => request.cacheWriteTokens ?? 0)),
    },
    guardrail: { flagged: statuses.filter((status) => status.includes('FLAGGED')).length, byStatus: count(statuses) },
    byModel: modelSummaries(trials, requests),
    routedReasons: count(requests.map((request) => request.routedReason).filter(Boolean)),
    writesExecuted: trials.filter((trial) => trial.writeExecuted).length,
    judge: judgeSummary(trials),
    byCategory: group(cases, (item) => item.category),
    byLanguage: group(cases, (item) => item.lang),
  }
}
