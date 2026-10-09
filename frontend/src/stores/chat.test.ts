import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ref } from 'vue'
import { ApiError } from '../api/http'
import type { AppInfo, ChatEvent } from '../api/types'
import { useChatStore } from './chat'

const streamChat = vi.fn()
vi.mock('../api/chat', () => ({ streamChat: (...args: unknown[]) => streamChat(...args) }))

const info = ref<AppInfo | null>(null)
vi.mock('../composables/useAppInfo', () => ({ useAppInfo: () => ({ info }) }))

function serverAllowsHistoryItems(maxHistoryItems: number) {
  info.value = { mode: 'offline', provider: 'offline', model: 'rule-based', maxHistoryItems }
}

const done: ChatEvent = {
  type: 'done', runId: 'r1', status: 'OK', provider: 'offline', model: 'rule-based',
  inputTokens: 10, outputTokens: 5, toolCalls: 1, iterations: 2, latencyMs: 30, costUsd: 0, truncated: false,
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
    info.value = null
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

  it('moves text written before a tool call into the preamble so the answer is only what follows', async () => {
    script([
      { type: 'token', text: 'Let me check ' },
      { type: 'token', text: 'the units.' },
      { type: 'tool_call', name: 'search_units', args: {} },
      { type: 'tool_result', name: 'search_units', summary: '6 units found', error: false },
      { type: 'token', text: 'There are 6 units' },
      done,
    ])
    const chat = useChatStore()

    await chat.send('how many units?')

    expect(chat.messages[1]).toMatchObject({ preamble: 'Let me check the units.', text: 'There are 6 units' })
  })

  it('separates the text of several model rounds in the preamble with a blank line', async () => {
    script([
      { type: 'token', text: 'First I will look up the unit.' },
      { type: 'tool_call', name: 'get_unit_details', args: {} },
      { type: 'tool_result', name: 'get_unit_details', summary: 'A-203', error: false },
      { type: 'token', text: 'Now the payments.' },
      { type: 'tool_call', name: 'get_payment_history', args: {} },
      { type: 'tool_result', name: 'get_payment_history', summary: '12 payments', error: false },
      { type: 'token', text: 'Rent is paid up to March.' },
      done,
    ])
    const chat = useChatStore()

    await chat.send('is A-203 paid up?')

    expect(chat.messages[1].preamble).toBe('First I will look up the unit.\n\nNow the payments.')
    expect(chat.messages[1].text).toBe('Rent is paid up to March.')
  })

  it('adds nothing to the preamble for a round that wrote no text or several tool calls at once', async () => {
    script([
      { type: 'token', text: 'Checking both.' },
      { type: 'tool_call', name: 'search_units', args: {} },
      { type: 'tool_call', name: 'find_overdue_tenants', args: {} },
      { type: 'tool_result', name: 'search_units', summary: '6 units', error: false },
      { type: 'tool_result', name: 'find_overdue_tenants', summary: '19 tenants', error: false },
      { type: 'tool_call', name: 'get_unit_details', args: {} },
      { type: 'token', text: 'Done.' },
      done,
    ])
    const chat = useChatStore()

    await chat.send('both')

    expect(chat.messages[1].preamble).toBe('Checking both.')
    expect(chat.messages[1].text).toBe('Done.')
  })

  it('keeps the text as the answer when the model calls no tool', async () => {
    script([{ type: 'token', text: 'I can only help with your portfolio.' }, done])
    const chat = useChatStore()

    await chat.send('what is the weather?')

    expect(chat.messages[1].text).toBe('I can only help with your portfolio.')
    expect(chat.messages[1].preamble).toBeUndefined()
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

  it('sends at most the 20 most recent history items until the server has reported its limit, keeping each question with its answer', async () => {
    const chat = useChatStore()
    for (let n = 1; n <= 13; n++) {
      script([{ type: 'token', text: `answer ${n}` }, done])
      await chat.send(`question ${n}`)
    }

    const history = streamChat.mock.calls[12][1] as { role: string; text: string }[]
    expect(history).toHaveLength(20)
    expect(history[0]).toEqual({ role: 'user', text: 'question 3' })
    expect(history[19]).toEqual({ role: 'assistant', text: 'answer 12' })
    expect(history.map((turn) => turn.role)).toEqual(Array.from({ length: 20 }, (_, i) => (i % 2 === 0 ? 'user' : 'assistant')))
  })

  it.each([
    { limit: 10, items: 10, firstQuestion: 'question 3' },
    { limit: 7, items: 6, firstQuestion: 'question 5' },
  ])('sends at most the $limit history items the server reports, as whole exchanges', async ({ limit, items, firstQuestion }) => {
    serverAllowsHistoryItems(limit)
    const chat = useChatStore()
    for (let n = 1; n <= 8; n++) {
      script([{ type: 'token', text: `answer ${n}` }, done])
      await chat.send(`question ${n}`)
    }

    const history = streamChat.mock.calls[7][1] as { role: string; text: string }[]
    expect(history).toHaveLength(items)
    expect(history[0]).toEqual({ role: 'user', text: firstQuestion })
    expect(history[items - 1]).toEqual({ role: 'assistant', text: 'answer 7' })
  })

  it.each([0, 1])('sends no history when the server allows only %i items', async (limit) => {
    serverAllowsHistoryItems(limit)
    const chat = useChatStore()
    for (let n = 1; n <= 3; n++) {
      script([{ type: 'token', text: `answer ${n}` }, done])
      await chat.send(`question ${n}`)
    }

    expect(streamChat.mock.calls[2][1]).toEqual([])
  })

  it('follows the limit once the server has reported it', async () => {
    const chat = useChatStore()
    for (let n = 1; n <= 4; n++) {
      script([{ type: 'token', text: `answer ${n}` }, done])
      await chat.send(`question ${n}`)
    }
    expect(streamChat.mock.calls[3][1]).toHaveLength(6)

    serverAllowsHistoryItems(2)
    script([{ type: 'token', text: 'answer 5' }, done])
    await chat.send('question 5')

    expect(streamChat.mock.calls[4][1]).toEqual([
      { role: 'user', text: 'question 4' },
      { role: 'assistant', text: 'answer 4' },
    ])
  })

  it('sends the whole history while it still fits', async () => {
    const chat = useChatStore()
    for (let n = 1; n <= 10; n++) {
      script([{ type: 'token', text: `answer ${n}` }, done])
      await chat.send(`question ${n}`)
    }

    expect(streamChat.mock.calls[9][1]).toHaveLength(18)
  })

  it('sends only the final answer as history, never the text written before tool calls', async () => {
    script([
      { type: 'token', text: 'Let me check the units.' },
      { type: 'tool_call', name: 'search_units', args: {} },
      { type: 'tool_result', name: 'search_units', summary: '6 units found', error: false },
      { type: 'token', text: 'There are 6 units' },
      done,
    ])
    script([{ type: 'token', text: 'ok' }, done])
    const chat = useChatStore()

    await chat.send('how many units?')
    await chat.send('thanks')

    expect(streamChat.mock.calls[1][1]).toEqual([
      { role: 'user', text: 'how many units?' },
      { role: 'assistant', text: 'There are 6 units' },
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

  it('keeps the error code from the stream and drops the failed exchange from the history', async () => {
    script([
      { type: 'error', message: 'The AI service is busy right now.', code: 'llm_overloaded' },
      { ...done, status: 'ERROR' },
    ])
    script([{ type: 'token', text: 'ok' }, done])
    const chat = useChatStore()

    await chat.send('one')
    expect(chat.messages[1]).toMatchObject({ errorCode: 'llm_overloaded', error: 'The AI service is busy right now.' })
    await chat.send('two')

    expect(streamChat.mock.calls[1][1]).toEqual([])
  })

  it('records that an answer was cut off', async () => {
    script([{ type: 'token', text: 'The portfolio has 150 un' }, { ...done, status: 'TRUNCATED', truncated: true }])
    const chat = useChatStore()

    await chat.send('how many units?')

    expect(chat.messages[1].text).toBe('The portfolio has 150 un')
    expect(chat.messages[1].usage).toMatchObject({ status: 'TRUNCATED', truncated: true })
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
