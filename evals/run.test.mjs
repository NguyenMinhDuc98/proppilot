// node --test evals/run.test.mjs
// The command line of the runner against a stub backend: what it leaves on disk while it runs and when it is stopped.
import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { existsSync, mkdtempSync, readFileSync, rmSync } from 'node:fs'
import { createServer } from 'node:http'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { test } from 'node:test'
import { fileURLToPath } from 'node:url'

const RUN = fileURLToPath(new URL('./run.mjs', import.meta.url))
const INFO = { mode: 'offline', provider: 'offline', model: 'rule-based', retrieval: 'keyword', leaseDocuments: 30 }
const ANSWER = [
  'event: tool_call\ndata: {"name":"search_units","args":{"city":"Riyadh"}}\n\n',
  'event: token\ndata: {"text":"A-105, A-305, A-405, B-205, B-305, B-505"}\n\n',
  'event: done\ndata: {"status":"OK","provider":"offline","model":"rule-based","costUsd":0,"latencyMs":5}\n\n',
].join('')

/** A backend that answers /api/info and hands each /api/chat request to `onChat(number)`, which answers or not. */
async function stubBackend(onChat) {
  let chats = 0
  const server = createServer((request, response) => {
    if (request.url === '/api/info') {
      response.setHeader('Content-Type', 'application/json').end(JSON.stringify(INFO))
      return
    }
    request.resume()
    chats += 1
    Promise.resolve(onChat(chats)).then((answer) => {
      if (answer) response.writeHead(200, { 'Content-Type': 'text/event-stream' }).end(ANSWER)
    }, () => response.writeHead(500).end('the stub failed'))
  })
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
  return {
    url: `http://127.0.0.1:${server.address().port}`,
    close: () => { server.closeAllConnections(); server.close() },
  }
}

/** A runner against `url` for the case en-01, killed when the test is over whatever happened. */
function startRunner(context, url, directory, ...args) {
  const out = join(directory, 'out.json')
  const child = spawn(process.execPath, [RUN, '--url', url, '--only', 'en-01', '--out', out,
    '--summary', join(directory, 'out.md'), ...args], {
    env: { ...process.env, ANTHROPIC_API_KEY: '', VOYAGE_API_KEY: '' }, stdio: 'ignore',
  })
  context.after(() => { child.kill('SIGKILL') })
  const exited = new Promise((resolve) => child.on('exit', (code, signal) => resolve({ code, signal })))
  return { child, exited, out }
}

/** A results directory and a backend that answers as `onChat(number, directory)` says; both are removed after the test. */
async function setUp(context, onChat) {
  const directory = mkdtempSync(join(tmpdir(), 'evals-run-'))
  const backend = await stubBackend((number) => onChat(number, directory))
  context.after(() => {
    backend.close()
    rmSync(directory, { recursive: true, force: true })
  })
  return { directory, backend }
}

const read = (file) => JSON.parse(readFileSync(file, 'utf8'))

test('the results are on disk after every sweep, marked incomplete until the last one', { timeout: 30_000 }, async (t) => {
  let afterFirstSweep = null
  const { directory, backend } = await setUp(t, (number, where) => {
    if (number === 2) afterFirstSweep = read(join(where, 'out.json'))
    return true
  })

  const { exited, out } = startRunner(t, backend.url, directory, '--runs', '2')

  assert.deepEqual(await exited, { code: 0, signal: null })
  assert.equal(afterFirstSweep.complete, false)
  assert.equal(afterFirstSweep.cases[0].trials.length, 1)
  assert.equal(afterFirstSweep.aggregate.runs, 1)
  assert.equal(afterFirstSweep.cases[0].trials[0].pass, true)
  const final = read(out)
  assert.equal(final.complete, true)
  assert.equal(final.cases[0].trials.length, 2)
  assert.match(readFileSync(join(directory, 'out.md'), 'utf8'), /PropPilot evals/)
})

test('a run that is stopped writes the runs it has and exits with the signal\'s code', { timeout: 30_000 }, async (t) => {
  let asked
  const secondAsked = new Promise((resolve) => { asked = resolve })
  const { directory, backend } = await setUp(t, (number) => {
    if (number === 1) return true
    asked()
    return false
  })
  const { child, exited, out } = startRunner(t, backend.url, directory, '--runs', '3')
  await secondAsked
  rmSync(out)

  child.kill('SIGTERM')
  const { code } = await exited

  assert.equal(code, 143)
  assert.ok(existsSync(out), 'the results were not written when the run was stopped')
  const stopped = read(out)
  assert.equal(stopped.complete, false)
  assert.equal(stopped.cases[0].trials.length, 1)
  assert.match(readFileSync(join(directory, 'out.md'), 'utf8'), /Incomplete run\./)
})
