// node --test evals/client.test.mjs
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { createClient } from './client.mjs'

const encoder = new TextEncoder()

/** A fetch that answers each request with the next response and remembers the requests. */
function stubFetch(...responses) {
  const requests = []
  const send = async (url, init = {}) => {
    requests.push({ url, init, body: init.body ? JSON.parse(init.body) : null })
    const next = responses.shift()
    if (next instanceof Error) throw next
    return next
  }
  return { send, requests }
}

const stream = (...blocks) => new Response(new ReadableStream({
  start(controller) {
    for (const block of blocks) controller.enqueue(encoder.encode(block))
    controller.close()
  },
}))

test('a question is posted with its history and the stream is folded into a run', async () => {
  const { send, requests } = stubFetch(stream(
    'event: tool_call\ndata: {"name":"search_units","args":{"city":"Riyadh"}}\n\n',
    'event: tool_result\ndata: {"name":"search_units","summary":"6 units","error":false}\n\n',
    'event: token\ndata: {"text":"Six units."}\n\n',
    'event: done\ndata: {"runId":"r1","status":"OK","model":"rule-based","costUsd":0}\n\n',
  ))
  const client = createClient({ baseUrl: 'http://backend:8080/', fetch: send })

  const run = await client.ask('Which are vacant?', [{ role: 'user', text: 'hi' }])

  assert.equal(requests[0].url, 'http://backend:8080/api/chat')
  assert.deepEqual(requests[0].body, { message: 'Which are vacant?', history: [{ role: 'user', text: 'hi' }] })
  assert.equal(run.answer, 'Six units.')
  assert.deepEqual(run.tools, ['search_units'])
  assert.equal(run.done.model, 'rule-based')
  assert.ok(run.elapsedMs >= 0)
})

test('a refused request is an error that says why, so a cap is not mistaken for a wrong answer', async () => {
  const { send } = stubFetch(new Response('Too many questions. Please wait a minute and try again.', { status: 429 }))

  await assert.rejects(createClient({ baseUrl: 'http://b', fetch: send }).ask('q'), /HTTP 429: Too many questions/)
})

test('what the backend says about itself is read, and silence is null', async () => {
  const info = { mode: 'offline', maxHistoryItems: 20 }
  const answered = stubFetch(Response.json(info))
  assert.deepEqual(await createClient({ baseUrl: 'http://b', fetch: answered.send }).info(), info)
  assert.equal(answered.requests[0].url, 'http://b/api/info')

  assert.equal(await createClient({ baseUrl: 'http://b', fetch: stubFetch(new Error('refused')).send }).info(), null)
  assert.equal(await createClient({ baseUrl: 'http://b', fetch: stubFetch(new Response('', { status: 500 })).send }).info(), null)
})

test('the status of an action is read from the server, and an action that cannot be read has none', async () => {
  const answered = stubFetch(Response.json({ id: 'a 1', status: 'PROPOSED' }))
  assert.equal(await createClient({ baseUrl: 'http://b', fetch: answered.send }).actionStatus('a 1'), 'PROPOSED')
  assert.equal(answered.requests[0].url, 'http://b/api/actions/a%201')

  assert.equal(await createClient({ baseUrl: 'http://b', fetch: stubFetch(new Response('', { status: 404 })).send }).actionStatus('x'), null)
})
