#!/usr/bin/env node
// Runs evals/questions.json against a running PropPilot backend, grades every answer and writes the results.
//
//   node evals/run.mjs [--url http://localhost:8080] [--runs 3] [--only en-,ar-] [--offline-only] [--judge]
//                      [--out evals/results/latest.json] [--summary evals/results/latest.md]
//                      [--delay-ms 0] [--compare evals/results/latest.json]
//
// Run `node evals/run.mjs --help` for what each option does, and see evals/README.md for the fields of a case and what
// each one checks. Start the backend with CHAT_RATE_LIMIT_PER_MINUTE=1000 and CHAT_DAILY_LIMIT=100000 to run without
// throttling. The write cases only propose: nothing is approved, and a run fails if any proposal was executed.
//
// Exit code: 0 when every case passed every run, 1 when one failed, 2 for a command line or a case selection that
// cannot be run.

import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { parseArgs, USAGE } from './args.mjs'
import { selectCases } from './cases.mjs'
import { createClient } from './client.mjs'
import { compareResults, describeComparison } from './compare.mjs'
import { limitsFrom } from './history.mjs'
import { judgeAnswer, judgeConfig } from './judge.mjs'
import { renderSummary } from './report.mjs'
import { buildResults, readPrevious } from './results.mjs'
import { runCases } from './runner.mjs'

const dir = dirname(fileURLToPath(import.meta.url))
const options = parseArgs(process.argv.slice(2), join(dir, 'results'))
if (options.error) {
  console.error(`${options.error}\n\n${USAGE}`)
  process.exit(2)
}
if (options.help) {
  console.log(USAGE)
  process.exit(0)
}

const allCases = JSON.parse(await readFile(join(dir, 'questions.json'), 'utf8'))
const { selected, skipped } = selectCases(allCases, { only: options.only, offlineOnly: options.offlineOnly })
if (selected.length === 0) {
  console.error(`No case has an id starting with ${options.only.join(' or ')}.`)
  process.exit(2)
}

let previous = null
if (options.compare) {
  try {
    previous = readPrevious(JSON.parse(await readFile(options.compare, 'utf8')))
  } catch (error) {
    console.error(`--compare ${options.compare}: ${error.message}`)
    process.exit(2)
  }
}

const client = createClient({ baseUrl: options.url })
const info = await client.info()
if (!info) console.warn(`Warning: ${options.url}/api/info did not answer; using the default history limits.`)

const configured = judgeConfig(process.env)
if (options.judge && !configured.enabled) {
  console.warn(`--judge: ${configured.reason}. Running without judge scores.`)
}
const judge = options.judge && configured.enabled ? (input) => judgeAnswer(configured, input) : null

const followUps = selected.reduce((n, item) => n + (item.priorQuestions?.length ?? 0), 0)
const requests = (selected.length + followUps) * options.runs
console.log(`${selected.length} cases x ${options.runs} run(s) = ${requests} chat requests`
  + (skipped.length ? `; ${skipped.length} case(s) skipped (offline: false)` : '')
  + `, against ${new URL(options.url).host}`)

const ranAt = new Date().toISOString()
const cases = await runCases({
  cases: selected,
  runs: options.runs,
  client,
  limits: limitsFrom(info),
  judge,
  delayMs: options.delayMs,
  onTrial: (item, trial, run) => {
    console.log(`${trial.pass ? 'PASS' : 'FAIL'}  ${item.id}  run ${run}/${options.runs}  ${item.question}`)
    if (!trial.pass) console.log(`      ${trial.reasons.join('; ')}`)
  },
})

const results = buildResults({
  ranAt,
  options: {
    runs: options.runs, only: options.only, offlineOnly: options.offlineOnly, judge: options.judge, delayMs: options.delayMs,
  },
  url: options.url,
  info,
  judge: {
    requested: options.judge,
    enabled: judge !== null,
    model: judge ? configured.model : null,
    reason: options.judge ? configured.reason ?? null : null,
  },
  cases,
  skipped,
})
const comparison = previous ? compareResults(results, previous) : null
const summary = renderSummary(results, comparison)

for (const file of [options.out, options.summary]) await mkdir(dirname(file), { recursive: true })
await writeFile(options.out, `${JSON.stringify(results, null, 2)}\n`)
await writeFile(options.summary, summary)

const a = results.aggregate
console.log(`\nPass rate ${(a.passRate * 100).toFixed(1)}% over ${a.runs} run(s), `
  + `${a.casesPassingEveryRun}/${a.cases} cases passed every run, ${a.flakyCases.length} flaky, `
  + `${a.failingCases.length} failing every run`)
console.log(`Latency ${Math.round(a.avgLatencyMs)} ms average, ${Math.round(a.p95LatencyMs)} ms p95; `
  + `cost $${a.avgCostUsd.toFixed(5)} per question`)
if (comparison) console.log(`\n${describeComparison(comparison).join('\n')}`)
console.log(`\nResults: ${options.out}\nSummary: ${options.summary}`)
process.exit(a.passedTrials === a.trials ? 0 : 1)
