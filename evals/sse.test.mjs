// node --test evals/sse.test.mjs
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { readEvents } from './sse.mjs'

const encoder = new TextEncoder()

async function* chunks(...parts) {
  for (const part of parts) yield typeof part === 'string' ? encoder.encode(part) : part
}

async function collect(body) {
  const events = []
  for await (const event of readEvents(body)) events.push(event)
  return events
}

test('each blank-line terminated block is an event with its data parsed', async () => {
  const events = await collect(chunks('event: token\ndata: {"text":"Hi"}\n\nevent: done\ndata: {"status":"OK"}\n\n'))

  assert.deepEqual(events, [{ event: 'token', data: { text: 'Hi' } }, { event: 'done', data: { status: 'OK' } }])
})

test('an event split across chunks, even in the middle of a line, is read once', async () => {
  const events = await collect(chunks('event: tok', 'en\nda', 'ta: {"text":"a b"}\n', '\nevent: done\ndata: {}\n\n'))

  assert.deepEqual(events.map((e) => e.event), ['token', 'done'])
  assert.equal(events[0].data.text, 'a b')
})

test('windows line endings are read like unix ones', async () => {
  const events = await collect(chunks('event: token\r\ndata: {"text":"x"}\r\n\r\n'))

  assert.deepEqual(events, [{ event: 'token', data: { text: 'x' } }])
})

test('a character of several bytes that arrives in two chunks is not broken', async () => {
  const bytes = encoder.encode('event: token\ndata: {"text":"أحمد"}\n\n')
  const cut = bytes.indexOf(0xd8) + 1

  const events = await collect(chunks(bytes.slice(0, cut), bytes.slice(cut)))

  assert.equal(events[0].data.text, 'أحمد')
})

test('a block without an event name or without data is skipped, as is a comment', async () => {
  const events = await collect(chunks(': keep-alive\n\nevent: token\n\ndata: {"text":"x"}\n\nevent: done\ndata: {}\n\n'))

  assert.deepEqual(events.map((e) => e.event), ['done'])
})

test('a block that is not finished when the body ends is dropped', async () => {
  const events = await collect(chunks('event: token\ndata: {"text":"x"}\n\nevent: done\ndata: {"sta'))

  assert.equal(events.length, 1)
})

test('data that is not JSON is an error, not a silent skip', async () => {
  await assert.rejects(collect(chunks('event: token\ndata: not json\n\n')), SyntaxError)
})
