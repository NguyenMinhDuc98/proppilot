#!/usr/bin/env node
// Runs evals/questions.json against a running PropPilot backend and prints pass rate, avg cost and avg latency.
//
//   node evals/run.mjs [--url http://localhost:8080] [--delay-ms 0] [--only en-,ar-]
//
// A question passes when every `expect` entry appears in the streamed answer, no `absent` entry does, and every tool
// in `tools` was called. An entry is a string, or an array of alternative wordings of which any one counts. Matching
// is case-insensitive and ignores number formatting (4,800 = 4800 = ٤٨٠٠), because models format numbers freely.
// Two optional fields are for the lease search: `mustCite` (a document code such as LSE-A-203, or an array of them)
// needs a citation marker of that document in the answer, and `absentTools` lists tools that must not be called.
// Grading is in grade.mjs. Start the backend with CHAT_RATE_LIMIT_PER_MINUTE=1000 to run without throttling.
//
// --only runs the cases whose id starts with one of the comma-separated prefixes: en-,ar- for the read questions,
// rag- for the lease search, write- for the write actions. The write cases leave proposals on the backend they run
// against and expect its seeded data, so run them against a local stack, or on a shared deployment right after its
// reset. The rag cases only read, and expect the 30 lease documents the application writes by default.

import { readFile, writeFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'
import { grade } from './grade.mjs'
import { summarize, summarizeByModel } from './summary.mjs'

const args = new Map()
for (let i = 2; i < process.argv.length; i++) {
  const key = process.argv[i]
  if (!key.startsWith('--')) continue
  const next = process.argv[i + 1]
  args.set(key.slice(2), next && !next.startsWith('--') ? next : 'true')
}
const baseUrl = (args.get('url') ?? 'http://localhost:8080').replace(/\/$/, '')
const delayMs = Number(args.get('delay-ms') ?? 0)
const only = (args.get('only') ?? '').split(',').filter(Boolean)
const dir = dirname(fileURLToPath(import.meta.url))

/** Minimal SSE reader: yields { event, data } for each blank-line terminated block. */
async function* sse(response) {
  const decoder = new TextDecoder()
  let buffer = ''
  for await (const chunk of response.body) {
    buffer += decoder.decode(chunk, { stream: true }).replace(/\r\n?/g, '\n')
    let boundary
    while ((boundary = buffer.indexOf('\n\n')) !== -1) {
      const block = buffer.slice(0, boundary)
      buffer = buffer.slice(boundary + 2)
      const event = /^event:\s*(.*)$/m.exec(block)?.[1]
      const data = [...block.matchAll(/^data:\s?(.*)$/gm)].map((m) => m[1]).join('\n')
      if (event && data) yield { event, data: JSON.parse(data) }
    }
  }
}

async function ask(question) {
  const response = await fetch(`${baseUrl}/api/chat`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
    body: JSON.stringify({ message: question, history: [] }),
  })
  if (!response.ok) throw new Error(`HTTP ${response.status}: ${await response.text()}`)
  const run = { answer: '', tools: [], done: null, error: null }
  for await (const { event, data } of sse(response)) {
    if (event === 'token') run.answer += data.text
    else if (event === 'tool_call') run.tools.push(data.name)
    else if (event === 'done') run.done = data
    else if (event === 'error') run.error = data.message
  }
  return run
}

const cases = JSON.parse(await readFile(join(dir, 'questions.json'), 'utf8'))
const questions = only.length ? cases.filter((item) => only.some((prefix) => item.id.startsWith(prefix))) : cases
if (questions.length === 0) {
  console.error(`No case has an id starting with ${only.join(' or ')}.`)
  process.exit(2)
}
const results = []
for (const item of questions) {
  try {
    const started = Date.now()
    const run = await ask(item.question)
    const verdict = grade(item, run)
    results.push({
      id: item.id, lang: item.lang, question: item.question, ...verdict,
      latencyMs: run.done?.latencyMs ?? Date.now() - started,
      costUsd: run.done?.costUsd ?? 0,
      provider: run.done?.provider, model: run.done?.model,
      answer: run.answer, tools: run.tools,
    })
  } catch (error) {
    results.push({ id: item.id, lang: item.lang, question: item.question, pass: false, error: String(error), missingFacts: [], unwantedFacts: [], missingTools: [], forbiddenToolsCalled: [], missingCitations: [], latencyMs: 0, costUsd: 0 })
  }
  const last = results.at(-1)
  console.log(`${last.pass ? 'PASS' : 'FAIL'}  ${last.id}  ${last.question}`)
  if (!last.pass) {
    console.log(`      missing facts: ${JSON.stringify(last.missingFacts)} unwanted facts: ${JSON.stringify(last.unwantedFacts)}`
      + ` tools: ${JSON.stringify(last.missingTools)} forbidden tools: ${JSON.stringify(last.forbiddenToolsCalled)}`
      + ` citations: ${JSON.stringify(last.missingCitations)} ${last.error ?? ''}`)
  }
  if (delayMs) await new Promise((resolve) => setTimeout(resolve, delayMs))
}

const overall = summarize(results)
const provider = results.find((r) => r.provider)?.provider
const models = summarizeByModel(results)
const rows = [['All', overall], ['English', summarize(results.filter((r) => r.lang === 'en'))], ['Arabic', summarize(results.filter((r) => r.lang === 'ar'))]]
// With more than one model answering, each one's averages are shown: the rows above mix them.
if (Object.keys(models).length > 1) rows.push(...Object.entries(models))

console.log(`\nProvider: ${provider ?? '?'} (${Object.keys(models).join(', ') || '?'})`)
console.log('| Set | Passed | Pass rate | Avg cost / question | Avg latency |')
console.log('|---|---|---|---|---|')
for (const [name, s] of rows) {
  console.log(`| ${name} | ${s.passed}/${s.total} | ${(s.passRate * 100).toFixed(0)}% | $${s.avgCostUsd.toFixed(5)} | ${Math.round(s.avgLatencyMs)} ms |`)
}

await writeFile(join(dir, 'results.json'), JSON.stringify({ ranAt: new Date().toISOString(), provider, models, overall, results }, null, 2))
process.exit(overall.passed === overall.total ? 0 : 1)
