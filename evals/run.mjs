#!/usr/bin/env node
// Runs evals/questions.json against a running PropPilot backend and prints pass rate, avg cost and avg latency.
//
//   node evals/run.mjs [--url http://localhost:8080] [--delay-ms 0]
//
// A question passes when every `expect` entry appears in the streamed answer and every tool in `tools` was called.
// An entry is a string, or an array of alternative wordings of which any one counts. Matching is case-insensitive
// and ignores number formatting (4,800 = 4800 = ٤٨٠٠), because models format numbers freely. Start the backend with CHAT_RATE_LIMIT_PER_MINUTE=1000 to run without throttling.

import { readFile, writeFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const args = new Map()
for (let i = 2; i < process.argv.length; i++) {
  const key = process.argv[i]
  if (!key.startsWith('--')) continue
  const next = process.argv[i + 1]
  args.set(key.slice(2), next && !next.startsWith('--') ? next : 'true')
}
const baseUrl = (args.get('url') ?? 'http://localhost:8080').replace(/\/$/, '')
const delayMs = Number(args.get('delay-ms') ?? 0)
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

function normalize(text) {
  return String(text)
    .toLowerCase()
    .replace(/[\u0660-\u0669]/g, (digit) => String(digit.charCodeAt(0) - 0x0660))
    .replace(/(\d),(?=\d{3}\b)/g, '$1')
}

function grade(item, run) {
  const answer = normalize(run.answer)
  const present = (fact) => [fact].flat().some((alternative) => answer.includes(normalize(alternative)))
  const missingFacts = item.expect.filter((fact) => !present(fact))
  const missingTools = (item.tools ?? []).filter((tool) => !run.tools.includes(tool))
  return { missingFacts, missingTools, pass: !run.error && missingFacts.length === 0 && missingTools.length === 0 }
}

const questions = JSON.parse(await readFile(join(dir, 'questions.json'), 'utf8'))
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
    results.push({ id: item.id, lang: item.lang, question: item.question, pass: false, error: String(error), missingFacts: [], missingTools: [], latencyMs: 0, costUsd: 0 })
  }
  const last = results.at(-1)
  console.log(`${last.pass ? 'PASS' : 'FAIL'}  ${last.id}  ${last.question}`)
  if (!last.pass) console.log(`      missing facts: ${JSON.stringify(last.missingFacts)} tools: ${JSON.stringify(last.missingTools)} ${last.error ?? ''}`)
  if (delayMs) await new Promise((resolve) => setTimeout(resolve, delayMs))
}

const avg = (xs) => (xs.length ? xs.reduce((a, b) => a + b, 0) / xs.length : 0)
const summarize = (rows) => ({
  total: rows.length,
  passed: rows.filter((r) => r.pass).length,
  passRate: rows.length ? rows.filter((r) => r.pass).length / rows.length : 0,
  avgCostUsd: avg(rows.map((r) => r.costUsd)),
  avgLatencyMs: avg(rows.map((r) => r.latencyMs)),
})
const overall = summarize(results)
const meta = results.find((r) => r.provider) ?? {}
const rows = [['All', overall], ['English', summarize(results.filter((r) => r.lang === 'en'))], ['Arabic', summarize(results.filter((r) => r.lang === 'ar'))]]

console.log(`\nProvider: ${meta.provider ?? '?'} (${meta.model ?? '?'})`)
console.log('| Set | Passed | Pass rate | Avg cost / question | Avg latency |')
console.log('|---|---|---|---|---|')
for (const [name, s] of rows) {
  console.log(`| ${name} | ${s.passed}/${s.total} | ${(s.passRate * 100).toFixed(0)}% | $${s.avgCostUsd.toFixed(5)} | ${Math.round(s.avgLatencyMs)} ms |`)
}

await writeFile(join(dir, 'results.json'), JSON.stringify({ ranAt: new Date().toISOString(), provider: meta.provider, model: meta.model, overall, results }, null, 2))
process.exit(overall.passed === overall.total ? 0 : 1)
