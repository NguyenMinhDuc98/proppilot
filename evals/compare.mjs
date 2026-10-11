// How a run differs from an earlier one: the pass rates, and which cases got better or worse.

const rateOf = (item) => (item.runs ? item.passes / item.runs : 0)

function delta(current, previous) {
  return { current, previous, delta: current - previous }
}

/**
 * @param {object} current a results document (format 2)
 * @param {object} previous an earlier one, as results.mjs readPrevious gives it
 */
export function compareResults(current, previous) {
  const before = new Map(previous.cases.map((item) => [item.id, item]))
  const after = new Map(current.cases.map((item) => [item.id, item]))
  const regressed = []
  const fixed = []
  for (const [id, now] of after) {
    const was = before.get(id)
    if (!was) continue
    const entry = { id, previous: `${was.passes}/${was.runs}`, current: `${now.passes}/${now.runs}` }
    if (rateOf(now) < rateOf(was)) regressed.push(entry)
    else if (rateOf(now) > rateOf(was)) fixed.push(entry)
  }
  const categories = Object.keys(current.aggregate.byCategory).filter((name) => previous.aggregate.byCategory[name])
  return {
    previousRanAt: previous.ranAt ?? null,
    passRate: delta(current.aggregate.passRate, previous.aggregate.passRate),
    avgLatencyMs: delta(current.aggregate.avgLatencyMs, previous.aggregate.avgLatencyMs),
    avgCostUsd: delta(current.aggregate.avgCostUsd, previous.aggregate.avgCostUsd),
    byCategory: Object.fromEntries(categories.map((name) => [
      name, delta(current.aggregate.byCategory[name].passRate, previous.aggregate.byCategory[name].passRate)])),
    regressed,
    fixed,
    added: [...after.keys()].filter((id) => !before.has(id)),
    removed: [...before.keys()].filter((id) => !after.has(id)),
  }
}

const MOST_IDS_LISTED = 8

/** Up to a few ids, and how many more there are. */
function ids(list) {
  if (list.length <= MOST_IDS_LISTED) return list.join(', ')
  return `${list.slice(0, MOST_IDS_LISTED).join(', ')} and ${list.length - MOST_IDS_LISTED} more`
}

const points = (value) => `${value >= 0 ? '+' : '-'}${Math.abs(value * 100).toFixed(1)} pt`
const percent = (value) => `${(value * 100).toFixed(1)}%`
const change = ({ previous, current }, format) => `${format(previous)} -> ${format(current)}`

/** The comparison as lines of text, for the console and for the summary. */
export function describeComparison(comparison) {
  const lines = [
    `Compared with the run of ${comparison.previousRanAt ?? 'an earlier date'}:`,
    `- pass rate ${change(comparison.passRate, percent)} (${points(comparison.passRate.delta)})`,
    `- average latency ${change(comparison.avgLatencyMs, (ms) => `${Math.round(ms)} ms`)}`,
    `- average cost ${change(comparison.avgCostUsd, (usd) => `$${usd.toFixed(5)}`)}`,
  ]
  for (const [name, moved] of Object.entries(comparison.byCategory)) {
    if (moved.delta !== 0) lines.push(`- ${name}: ${change(moved, percent)} (${points(moved.delta)})`)
  }
  const list = (entries) => entries.map((e) => `${e.id} (${e.previous} -> ${e.current})`).join(', ')
  if (comparison.regressed.length) lines.push(`- worse: ${list(comparison.regressed)}`)
  if (comparison.fixed.length) lines.push(`- better: ${list(comparison.fixed)}`)
  if (comparison.added.length) lines.push(`- cases not in the earlier run: ${ids(comparison.added)}`)
  if (comparison.removed.length) lines.push(`- cases not in this run: ${ids(comparison.removed)}`)
  return lines
}
