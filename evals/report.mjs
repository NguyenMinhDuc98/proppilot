// The markdown summary of a run: what it was run against, how it did overall, by category and by language, and which
// cases failed or were flaky. Pure text from a results document (results.mjs), so it is tested without a backend.

import { CATEGORY_ORDER } from './cases.mjs'
import { describeComparison } from './compare.mjs'

const LANGUAGES = { en: 'English', ar: 'Arabic' }
const MOST_REASON_LENGTH = 240

const percent = (value) => `${(value * 100).toFixed(1)}%`
const money = (value) => `$${value.toFixed(5)}`
const duration = (ms) => (ms < 1000 ? `${Math.round(ms)} ms` : `${(ms / 1000).toFixed(2)} s`)
const cell = (text) => String(text).replace(/\|/g, '\\|').replace(/\s*\n\s*/g, ' ')

function table(header, rows) {
  return [
    `| ${header.join(' | ')} |`,
    `|${header.map(() => '---').join('|')}|`,
    ...rows.map((row) => `| ${row.map(cell).join(' | ')} |`),
  ].join('\n')
}

function judgeLine(judge, summary) {
  if (!judge.requested) return 'not run (`--judge` was not given)'
  if (!judge.enabled) return `not run (${judge.reason})`
  const errors = summary.errors ? `, ${summary.errors} could not be judged` : ''
  return `ran with ${judge.model}: ${summary.judged} answers scored${errors}`
}

function header(results) {
  const { backend, options, aggregate, judge, skipped } = results
  const answered = Object.entries(aggregate.models).map(([model, n]) => `${model} (${n})`).join(', ')
  const models = answered || backend.model || '?'
  const skippedNote = skipped.length ? `, ${skipped.length} skipped (\`offline: false\`): ${skipped.join(', ')}` : ''
  return table(['', ''], [
    ['Provider', backend.provider ?? '?'],
    ['Model(s) that answered', models],
    ['Runs per case', options.runs],
    ['Cases', `${aggregate.cases}${skippedNote}`],
    ['Date', results.ranAt],
    ['Backend', `${backend.host}${backend.retrieval ? `, lease search by ${backend.retrieval}` : ''}`],
    ['LLM judge', judgeLine(judge, aggregate.judge)],
  ])
}

function offlineNote(results) {
  if (results.backend.provider !== 'offline') return ''
  return '> **Offline run.** The assistant was the built-in rule-based one (zero cost). This checks the data, the '
    + 'tools, the streaming and the grading. It says nothing about how well Claude answers: the cases that only a '
    + 'real model can pass (`offline: false`) were not run, and neither was the LLM judge, unless the table above '
    + 'says so. Its token counts are estimates of about four characters each, and no prompt cache was involved.\n\n'
}

function overall(a) {
  return table(['Measure', 'Value'], [
    ['Pass rate (mean over runs)', `${percent(a.passRate)} (${a.passedTrials}/${a.trials} runs of cases)`],
    ['Standard deviation of the pass rate across runs',
      a.runs > 1 ? `${(a.passRateStdDev * 100).toFixed(2)} pt` : 'n/a (one run)'],
    ['Cases that passed every run', `${a.casesPassingEveryRun}/${a.cases}`],
    ['Flaky cases (passed some runs)', a.flakyCases.length],
    ['Cases that failed every run', a.failingCases.length],
    ['Average latency', duration(a.avgLatencyMs)],
    ['p95 latency', duration(a.p95LatencyMs)],
    ['Average cost per question', money(a.avgCostUsd)],
    ['Total cost, with earlier turns', money(a.totalCostUsd)],
    ['Cache read / write tokens', `${a.tokens.cacheRead} / ${a.tokens.cacheWrite}`],
    ['Input / output tokens', `${a.tokens.input} / ${a.tokens.output}`],
    ['Answers the guardrail flagged', a.guardrail.flagged],
    ['Writes executed by a run', a.writesExecuted],
  ])
}

function groups(heading, entries) {
  const rows = entries.map(([name, g]) => [name, g.cases, `${g.passed}/${g.trials}`, percent(g.passRate)])
  return `${heading}\n\n${table(['', 'Cases', 'Passed', 'Pass rate'], rows)}`
}

function failures(cases) {
  const rows = cases.filter((item) => item.passes < item.runs).map((item) => {
    const failing = item.trials.filter((trial) => !trial.pass)
    const why = [...new Set(failing.flatMap((trial) => trial.reasons))].join('; ')
    const shown = why.length > MOST_REASON_LENGTH ? `${why.slice(0, MOST_REASON_LENGTH)}...` : why
    return [item.id, item.category, item.lang, `${item.passes}/${item.runs}${item.flaky ? ' (flaky)' : ''}`, shown]
  })
  if (rows.length === 0) return 'Every case passed every run.'
  return table(['Case', 'Category', 'Language', 'Passed', 'Why it failed'], rows)
}

/**
 * @param {object} results a results document (format 2)
 * @param {object|null} comparison from compare.mjs, when an earlier run was given
 */
export function renderSummary(results, comparison = null) {
  const { aggregate } = results
  const categories = Object.entries(aggregate.byCategory)
    .sort(([a], [b]) => CATEGORY_ORDER.indexOf(a) - CATEGORY_ORDER.indexOf(b))
  const languages = Object.entries(aggregate.byLanguage).map(([lang, g]) => [LANGUAGES[lang] ?? lang, g])
  const sections = [
    '# PropPilot evals',
    header(results),
    offlineNote(results).trimEnd(),
    '## Overall',
    overall(aggregate),
    groups('## By category', categories),
    groups('## By language', languages),
    '## Cases that failed or were flaky',
    failures(results.cases),
  ]
  if (Object.keys(aggregate.routedReasons).length > 1) {
    const rows = Object.entries(aggregate.routedReasons).map(([reason, n]) => [reason, n])
    sections.push('## Why each model answered', table(['Routed reason', 'Runs'], rows))
  }
  if (comparison) sections.push('## Compared with an earlier run', describeComparison(comparison).join('\n'))
  return `${sections.filter(Boolean).join('\n\n')}\n`
}
