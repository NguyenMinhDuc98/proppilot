import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ChatEvent } from './types'
import { streamChat, streamConfirmation } from './chat'

const fetchMock = vi.fn()

function sse(...events: [string, unknown][]) {
  const text = events.map(([event, data]) => `event:${event}\ndata:${JSON.stringify(data)}\n\n`).join('')
  fetchMock.mockResolvedValueOnce(new Response(text))
}

async function collect(stream: AsyncGenerator<ChatEvent>) {
  const out: ChatEvent[] = []
  for await (const event of stream) out.push(event)
  return out
}

const sentBody = () => JSON.parse((fetchMock.mock.calls[0][1] as RequestInit).body as string)

beforeEach(() => {
  fetchMock.mockReset()
  vi.stubGlobal('fetch', fetchMock)
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('streamChat', () => {
  it('posts the question with the history', async () => {
    sse(['token', { text: 'Hi' }])

    await collect(streamChat('hello', [{ role: 'user', text: 'earlier' }]))

    expect(fetchMock.mock.calls[0][0]).toBe('/api/chat')
    expect(sentBody()).toEqual({ message: 'hello', history: [{ role: 'user', text: 'earlier' }] })
  })

  it('yields a proposed action with its fields next to the event type', async () => {
    const action = { id: 'a1', kind: 'RECORD_PAYMENT', status: 'PROPOSED', summaryEn: 'Record', summaryAr: 'تسجيل' }
    sse(['tool_result', { name: 'record_payment', summary: 'Proposed', error: false }], ['action_proposed', action])

    const events = await collect(streamChat('record 100', []))

    expect(events[1]).toEqual({ type: 'action_proposed', ...action })
  })

  it('throws an ApiError for a refused request', async () => {
    fetchMock.mockResolvedValueOnce(new Response('{}', { status: 429, statusText: 'Too Many Requests' }))

    await expect(collect(streamChat('hello', []))).rejects.toMatchObject({ status: 429 })
  })
})

describe('streamConfirmation', () => {
  it('asks for the confirmation of an action by its id and sends no question', async () => {
    sse(['token', { text: 'Done.' }])

    const events = await collect(streamConfirmation('a1', [{ role: 'assistant', text: 'earlier' }]))

    expect(sentBody()).toEqual({ actionId: 'a1', history: [{ role: 'assistant', text: 'earlier' }] })
    expect(sentBody()).not.toHaveProperty('message')
    expect(events).toEqual([{ type: 'token', text: 'Done.' }])
  })

  it('keeps the status of a refusal such as an action that was already confirmed', async () => {
    fetchMock.mockResolvedValueOnce(new Response('{}', { status: 409, statusText: 'Conflict' }))

    await expect(collect(streamConfirmation('a1', []))).rejects.toMatchObject({ status: 409 })
  })
})
