// How the runs of a whole eval are summed up: per case, per category and language, and overall. Kept apart from
// run.mjs so it can be tested without a backend (aggregate.test.mjs).

const mean = (values) => (values.length ? values.reduce((a, b) => a + b, 0) / values.length : 0)
const sum = (values) => values.reduce((a, b) => a + b, 0)
const count = (values) => values.reduce((counts, value) => ({ ...counts, [value]: (counts[value] ?? 0) + 1 }), {})

/** What a trial cost, with the earlier turns of its conversation. */
const costWithEarlierTurns = (trial) => (trial.costUsd ?? 0)
  + sum((trial.priorTurns ?? []).map((turn) => turn.costUsd ?? 0))

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
 * @param {{id: string, category: string, lang: string, trials: object[]}[]} cases each with one trial per run
 * @param {number} runs how many times each case was run
 */
export function aggregate(cases, runs) {
  const trials = cases.flatMap((item) => item.trials)
  const perRun = Array.from({ length: runs }, (_, index) => (
    cases.length ? cases.filter((item) => item.trials[index]?.pass).length / cases.length : 0))
  const outcomes = cases.map((item) => ({ id: item.id, ...caseOutcome(item.trials) }))
  const latencies = trials.map((trial) => trial.latencyMs).filter((latency) => typeof latency === 'number')
  const statuses = trials.map((trial) => trial.guardrailStatus).filter(Boolean)
  return {
    cases: cases.length,
    runs,
    trials: trials.length,
    passedTrials: trials.filter((trial) => trial.pass).length,
    passRate: mean(perRun),
    passRateStdDev: standardDeviation(perRun),
    passRatePerRun: perRun,
    casesPassingEveryRun: outcomes.filter((o) => o.passes === o.runs).length,
    flakyCases: outcomes.filter((o) => o.flaky).map((o) => o.id),
    failingCases: outcomes.filter((o) => o.passes === 0).map((o) => o.id),
    avgLatencyMs: mean(latencies),
    p95LatencyMs: percentile(latencies, 95),
    avgCostUsd: mean(trials.map((trial) => trial.costUsd ?? 0)),
    totalCostUsd: sum(trials.map(costWithEarlierTurns)),
    tokens: {
      input: sum(trials.map((trial) => trial.inputTokens ?? 0)),
      output: sum(trials.map((trial) => trial.outputTokens ?? 0)),
      cacheRead: sum(trials.map((trial) => trial.cacheReadTokens ?? 0)),
      cacheWrite: sum(trials.map((trial) => trial.cacheWriteTokens ?? 0)),
    },
    guardrail: { flagged: statuses.filter((status) => status.includes('FLAGGED')).length, byStatus: count(statuses) },
    models: count(trials.map((trial) => trial.model).filter(Boolean)),
    routedReasons: count(trials.map((trial) => trial.routedReason).filter(Boolean)),
    writesExecuted: trials.filter((trial) => trial.writeExecuted).length,
    judge: judgeSummary(trials),
    byCategory: group(cases, (item) => item.category),
    byLanguage: group(cases, (item) => item.lang),
  }
}
