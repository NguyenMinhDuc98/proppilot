import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../api/http'
import type { ChatEvent } from '../api/types'
import { useChatStore } from './chat'

const streamChat = vi.fn()
vi.mock('../api/chat', () => ({ streamChat: (...args: unknown[]) => streamChat(...args) }))

const done: ChatEvent = {
  type: 'done', runId: 'r1', status: 'OK', provider: 'offline', model: 'rule-based',
  inputTokens: 10, outputTokens: 5, toolCalls: 1, iterations: 2, latencyMs: 30, costUsd: 0,
}

function script(events: ChatEvent[]) {
  streamChat.mockImplementationOnce(async function* () {
    yield* events
  })
}

describe('chat store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    streamChat.mockReset()
  })

  it('shows tool chips, streams tokens and records usage', async () => {
    script([
      { type: 'tool_call', name: 'search_units', args: { city: 'Riyadh' } },
      { type: 'tool_result', name: 'search_units', summary: '6 units found', error: false },
      { type: 'token', text: '6 units ' },
      { type: 'token', text: 'found' },
      done,
    ])
    const chat = useChatStore()

    await chat.send('vacant units?')

    const [user, assistant] = chat.messages
    expect(user).toMatchObject({ role: 'user', text: 'vacant units?' })
    expect(assistant.text).toBe('6 units found')
    expect(assistant.tools).toEqual([
      { name: 'search_units', args: { city: 'Riyadh' }, status: 'done', summary: '6 units found' },
    ])
    expect(assistant.usage?.toolCalls).toBe(1)
    expect(assistant.streaming).toBe(false)
    expect(chat.busy).toBe(false)
  })

  it('marks a failed tool call', async () => {
    script([
      { type: 'tool_call', name: 'get_unit_details', args: {} },
      { type: 'tool_result', name: 'get_unit_details', summary: 'No unit', error: true },
      done,
    ])
    const chat = useChatStore()

    await chat.send('details')

    expect(chat.messages[1].tools[0].status).toBe('failed')
  })

  it('sends only completed turns as history', async () => {
    script([{ type: 'token', text: 'first answer' }, done])
    script([{ type: 'token', text: 'second answer' }, done])
    const chat = useChatStore()

    await chat.send('first')
    await chat.send('second')

    expect(streamChat.mock.calls[0][1]).toEqual([])
    expect(streamChat.mock.calls[1][1]).toEqual([
      { role: 'user', text: 'first' },
      { role: 'assistant', text: 'first answer' },
    ])
  })

  it('drops a failed exchange from the history', async () => {
    streamChat.mockImplementationOnce(async function* () {
      throw new ApiError(429, 'Too many questions')
    })
    script([{ type: 'token', text: 'ok' }, done])
    const chat = useChatStore()

    await chat.send('one')
    expect(chat.messages[1].error).toBe('Too many questions')
    await chat.send('two')

    expect(streamChat.mock.calls[1][1]).toEqual([])
  })

  it('ignores empty input and concurrent sends', async () => {
    let release: () => void = () => {}
    streamChat.mockImplementationOnce(async function* () {
      await new Promise<void>((resolve) => (release = resolve))
      yield done
    })
    const chat = useChatStore()

    await chat.send('   ')
    expect(chat.messages).toHaveLength(0)

    const first = chat.send('first')
    await chat.send('second')
    expect(chat.messages).toHaveLength(2)
    release()
    await first
    expect(streamChat).toHaveBeenCalledTimes(1)
  })

  it('does not report an error when the user stops the stream', async () => {
    streamChat.mockImplementationOnce(async function* (_m: string, _h: unknown, signal: AbortSignal) {
      await new Promise((_, reject) => signal.addEventListener('abort', () => reject(new DOMException('x', 'AbortError'))))
      yield done
    })
    const chat = useChatStore()

    const sending = chat.send('long question')
    chat.stop()
    await sending

    expect(chat.messages[1].error).toBeUndefined()
    expect(chat.messages[1].streaming).toBe(false)
  })

  it('clear resets the conversation', async () => {
    script([{ type: 'token', text: 'hi' }, done])
    const chat = useChatStore()
    await chat.send('hello')

    chat.clear()

    expect(chat.messages).toEqual([])
  })
})
