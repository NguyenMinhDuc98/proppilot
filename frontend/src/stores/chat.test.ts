import { flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ref } from 'vue'
import { ApiError } from '../api/http'
import type { ActionView, AppInfo, ChatEvent, LeaseSource } from '../api/types'
import { buildAnswer } from '../composables/answer'
import { paymentAction } from '../test/actionFixtures'
import { lateFee, noticePart } from '../test/leaseFixtures'
import { useChatStore } from './chat'

const streamChat = vi.fn()
const streamConfirmation = vi.fn()
vi.mock('../api/chat', () => ({
  streamChat: (...args: unknown[]) => streamChat(...args),
  streamConfirmation: (...args: unknown[]) => streamConfirmation(...args),
}))

const info = ref<AppInfo | null>(null)
vi.mock('../composables/useAppInfo', () => ({ useAppInfo: () => ({ info }) }))

function serverAllows(limits: Pick<AppInfo, 'maxHistoryItems'> | Pick<AppInfo, 'maxHistoryTurnLength'>) {
  info.value = {
    mode: 'offline', provider: 'offline', model: 'rule-based', maxHistoryItems: 20, maxHistoryTurnLength: 4000, ...limits,
  }
}

const serverAllowsHistoryItems = (maxHistoryItems: number) => serverAllows({ maxHistoryItems })
const serverAllowsTurnsOf = (maxHistoryTurnLength: number) => serverAllows({ maxHistoryTurnLength })

const done: ChatEvent = {
  type: 'done', runId: 'r1', status: 'OK', provider: 'offline', model: 'rule-based', routedReason: 'DEFAULT',
  guardrailStatus: 'OK',
  inputTokens: 10, outputTokens: 5, cacheReadTokens: 0, cacheWriteTokens: 0, toolCalls: 1, iterations: 2,
  latencyMs: 30, costUsd: 0, truncated: false,
}

function script(events: ChatEvent[]) {
  streamChat.mockImplementationOnce(async function* () {
    yield* events
  })
}

function scriptConfirmation(events: ChatEvent[]) {
  streamConfirmation.mockImplementationOnce(async function* () {
    yield* events
  })
}

/** An `action_proposed` event as the stream delivers it: the action's fields next to the event type. */
const proposal = (action: ActionView): ChatEvent => ({ type: 'action_proposed', ...action })

describe('chat store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    streamChat.mockReset()
    streamConfirmation.mockReset()
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

  describe('when the assistant is asked to answer again', () => {
    const leaseSearch = (ref: string): ChatEvent => ({
      type: 'tool_result', name: 'search_lease_documents', summary: '1 clause', error: false,
      sources: [{ ...lateFee, ref }],
    })

    it('drops the answer streamed so far and keeps the tools, the clauses they returned and the note before them', async () => {
      script([
        { type: 'token', text: 'Let me look at the lease.' },
        { type: 'tool_call', name: 'search_lease_documents', args: {} },
        leaseSearch('LSE-A-203#5'),
        { type: 'token', text: 'The late fee is SAR 90 [LSE-A-203#5].' },
        { type: 'revise', reason: 'grounding' },
        { type: 'token', text: 'The late fee is SAR 75 [LSE-A-203#5].' },
        { ...done, guardrailStatus: 'RETRIED_OK' },
      ])
      const chat = useChatStore()

      await chat.send('what is the late fee?')

      const answer = chat.messages[1]
      expect(answer.text).toBe('The late fee is SAR 75 [LSE-A-203#5].')
      expect(answer.preamble).toBe('Let me look at the lease.')
      expect(answer.sources.map((source) => source.ref)).toEqual(['LSE-A-203#5'])
      expect(answer.tools).toHaveLength(1)
      expect(answer.guardrail).toEqual({ status: 'RETRIED_OK', values: [] })
    })

    it('lets the new answer cite a clause the search of the dropped one returned, without searching again', async () => {
      script([
        { type: 'tool_call', name: 'search_lease_documents', args: {} },
        leaseSearch('LSE-A-203#5'),
        { type: 'token', text: 'The late fee is SAR 90 [LSE-A-203#5].' },
        { type: 'revise', reason: 'grounding' },
        { type: 'token', text: 'The late fee is SAR 75 [LSE-A-203#5].' },
        { ...done, guardrailStatus: 'RETRIED_OK' },
      ])
      const chat = useChatStore()

      await chat.send('what is the late fee?')

      const { text, sources } = chat.messages[1]
      expect(buildAnswer(text, sources).cited.map((source) => source.ref)).toEqual(['LSE-A-203#5'])
    })

    it('keeps the clauses of both answers, which the text of the new one cites as it wishes', async () => {
      script([
        { type: 'tool_call', name: 'search_lease_documents', args: {} },
        leaseSearch('LSE-A-203#5'),
        { type: 'token', text: 'The late fee is SAR 90 [LSE-A-203#5].' },
        { type: 'revise', reason: 'grounding' },
        { type: 'tool_call', name: 'search_lease_documents', args: {} },
        leaseSearch('LSE-A-203#6'),
        { type: 'token', text: 'The notice period is 60 days [LSE-A-203#6].' },
        { ...done, guardrailStatus: 'RETRIED_OK' },
      ])
      const chat = useChatStore()

      await chat.send('what does the lease say?')

      const { text, sources } = chat.messages[1]
      expect(sources.map((source) => source.ref)).toEqual(['LSE-A-203#5', 'LSE-A-203#6'])
      // Only a clause the text cites is shown as a chip, so the first answer's clause is not.
      expect(buildAnswer(text, sources).cited.map((source) => source.ref)).toEqual(['LSE-A-203#6'])
    })

    it('shows the re-checking state until the new answer starts, and not after the stream ended', async () => {
      let release: () => void = () => {}
      const held = new Promise<void>((resolve) => { release = resolve })
      streamChat.mockImplementationOnce(async function* () {
        yield { type: 'token', text: 'The portfolio has 151 units.' } as ChatEvent
        yield { type: 'revise', reason: 'grounding' } as ChatEvent
        await held
        yield { type: 'token', text: 'The portfolio has 150 units.' } as ChatEvent
        yield { ...done, guardrailStatus: 'RETRIED_OK' } as ChatEvent
      })
      const chat = useChatStore()

      const sending = chat.send('how many units?')
      await flushPromises()
      expect(chat.messages[1]).toMatchObject({ text: '', streaming: true })
      expect(chat.messages[1].replaced).toBeDefined()

      release()
      await sending
      expect(chat.messages[1].text).toBe('The portfolio has 150 units.')
      expect(chat.messages[1].replaced).toBeUndefined()
    })

    it('puts the earlier answer back with its note, without what the new one wrote, when it never completed', async () => {
      script([
        { type: 'token', text: 'Let me look at the lease.' },
        { type: 'tool_call', name: 'search_lease_documents', args: {} },
        leaseSearch('LSE-A-203#5'),
        { type: 'token', text: 'The late fee is SAR 75 [LSE-A-203#5] and the notice is 60 days.' },
        { type: 'revise', reason: 'grounding' },
        { type: 'token', text: 'Checking ' },
        { type: 'tool_call', name: 'search_units', args: {} },
        { type: 'tool_result', name: 'search_units', summary: '150 units', error: false },
        { type: 'revise', reason: 'grounding' },
        { type: 'token', text: 'The late fee is SAR 75 [LSE-A-203#5] and the notice is 60 days.' },
        { type: 'guardrail', status: 'unverified', values: [{ kind: 'NUMBER', value: '60' }] },
        { ...done, guardrailStatus: 'FLAGGED' },
      ])
      const chat = useChatStore()

      await chat.send('late fee and notice?')

      const answer = chat.messages[1]
      expect(answer.text).toBe('The late fee is SAR 75 [LSE-A-203#5] and the notice is 60 days.')
      expect(answer.preamble).toBe('Let me look at the lease.')
      expect(answer.sources.map((source) => source.ref)).toEqual(['LSE-A-203#5'])
      expect(answer.error).toBeUndefined()
      expect(answer.guardrail).toEqual({ status: 'FLAGGED', values: [{ kind: 'NUMBER', value: '60' }] })
    })

    it('sends only the final answer as history for the next question', async () => {
      script([
        { type: 'token', text: 'The portfolio has 151 units.' },
        { type: 'revise', reason: 'grounding' },
        { type: 'token', text: 'The portfolio has 150 units.' },
        { ...done, guardrailStatus: 'RETRIED_OK' },
      ])
      script([{ type: 'token', text: 'Yes.' }, done])
      const chat = useChatStore()

      await chat.send('how many units?')
      await chat.send('and vacant ones?')

      expect(streamChat.mock.calls[1][1]).toEqual([
        { role: 'user', text: 'how many units?' },
        { role: 'assistant', text: 'The portfolio has 150 units.' },
      ])
    })
  })

  describe('what the guardrail found', () => {
    it('keeps the values of an answer it could not match to the data with its verdict', async () => {
      script([
        { type: 'token', text: 'The portfolio has 151 units.' },
        { type: 'guardrail', status: 'unverified', values: [{ kind: 'NUMBER', value: '151' }] },
        { ...done, guardrailStatus: 'FLAGGED' },
      ])
      const chat = useChatStore()

      await chat.send('how many units?')

      expect(chat.messages[1].text).toBe('The portfolio has 151 units.')
      expect(chat.messages[1].guardrail).toEqual({ status: 'FLAGGED', values: [{ kind: 'NUMBER', value: '151' }] })
    })

    it('takes the verdict of a run that was asked again and is still flagged from the done event', async () => {
      script([
        { type: 'token', text: 'The portfolio has 151 units.' },
        { type: 'guardrail', status: 'unverified', values: [{ kind: 'NUMBER', value: '151' }] },
        { ...done, guardrailStatus: 'RETRIED_FLAGGED' },
      ])
      const chat = useChatStore()

      await chat.send('how many units?')

      expect(chat.messages[1].guardrail?.status).toBe('RETRIED_FLAGGED')
    })

    it('has a checked answer with no values, and no verdict for an answer that was not checked', async () => {
      script([{ type: 'token', text: 'Yes.' }, done])
      script([{ type: 'token', text: 'Yes.' }, { ...done, guardrailStatus: null }])
      const chat = useChatStore()

      await chat.send('one')
      await chat.send('two')

      expect(chat.messages[1].guardrail).toEqual({ status: 'OK', values: [] })
      expect(chat.messages[3].guardrail).toBeUndefined()
    })

    it('keeps the model, why it was chosen and the cache tokens of the run with its usage', async () => {
      script([
        { type: 'token', text: 'Done.' },
        {
          ...done, provider: 'anthropic', model: 'claude-sonnet-4-5', routedReason: 'COMPLEX',
          inputTokens: 12, cacheReadTokens: 3400, cacheWriteTokens: 269,
        },
      ])
      const chat = useChatStore()

      await chat.send('a complex question')

      expect(chat.messages[1].usage).toMatchObject({
        model: 'claude-sonnet-4-5', routedReason: 'COMPLEX', cacheReadTokens: 3400, cacheWriteTokens: 269,
      })
    })
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

  describe('an answer longer than the server accepts for one history turn', () => {
    /** Sends a question the model answers with `answer`, then another, and returns the history the second carried. */
    async function historyAfter(answer: string): Promise<{ role: string; text: string }[]> {
      script([{ type: 'token', text: answer }, done])
      script([{ type: 'token', text: 'ok' }, done])
      const chat = useChatStore()
      await chat.send('what are the house rules?')
      await chat.send('and parking?')
      return streamChat.mock.calls[1][1]
    }

    it('is cut to the 4000 characters assumed before the server has reported its limit, and its question is kept', async () => {
      const history = await historyAfter('The house rules are quoted below. '.repeat(150))

      expect(history).toHaveLength(2)
      expect(history[0]).toEqual({ role: 'user', text: 'what are the house rules?' })
      expect(history[1].role).toBe('assistant')
      expect(history[1].text.length).toBeLessThanOrEqual(4000)
      expect(history[1].text.startsWith('The house rules are quoted below.')).toBe(true)
      expect(history[1].text.endsWith('…')).toBe(true)
    })

    it('is cut to the limit the server reports', async () => {
      serverAllowsTurnsOf(300)

      const history = await historyAfter('x'.repeat(5000))

      expect(history[1].text).toBe(`${'x'.repeat(299)}…`)
    })

    it('is cut at the end of a line when one is in the second half of what fits', async () => {
      const answer = `${'a'.repeat(3000)}\n${'b'.repeat(3000)}`

      const history = await historyAfter(answer)

      expect(history[1].text).toMatch(/^a{3000}…$/)
    })

    it('is cut where it fits, not at an early line end that would leave too little of it', async () => {
      const answer = `${'a'.repeat(100)}\n${'b'.repeat(5000)}`

      const history = await historyAfter(answer)

      expect(history[1].text).toMatch(/^a{100}\nb{3898}…$/)
      expect(history[1].text).toHaveLength(4000)
    })

    it('is not cut inside a character that takes two code units', async () => {
      const history = await historyAfter(`${'x'.repeat(3998)}${'😀'.repeat(10)}`)

      expect(history[1].text).toMatch(/^x{3998}…$/)
    })

    it('is left whole when it is exactly as long as the server accepts', async () => {
      const history = await historyAfter('y'.repeat(4000))

      expect(history[1].text).toMatch(/^y{4000}$/)
    })

    it('still lets the next question be asked after the exchange, and again after that', async () => {
      script([{ type: 'token', text: 'z'.repeat(5000) }, done])
      script([{ type: 'token', text: 'second' }, done])
      script([{ type: 'token', text: 'third' }, done])
      const chat = useChatStore()

      await chat.send('one')
      await chat.send('two')
      await chat.send('three')

      const lengths = (streamChat.mock.calls[2][1] as { text: string }[]).map((turn) => turn.text.length)
      expect(lengths).toEqual([3, 4000, 3, 6])
    })
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

describe('chat store lease sources', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    streamChat.mockReset()
    streamConfirmation.mockReset()
    info.value = null
  })

  const search = (sources?: LeaseSource[], error = false): ChatEvent[] => [
    { type: 'tool_call', name: 'search_lease_documents', args: { query: 'late fee' } },
    { type: 'tool_result', name: 'search_lease_documents', summary: 'clauses', error, ...(sources ? { sources } : {}) },
  ]

  it('collects the clauses of a lease search into the answer it belongs to', async () => {
    script([...search([lateFee, noticePart]), { type: 'token', text: 'Fee [LSE-A-504#5].' }, done])
    const chat = useChatStore()

    await chat.send('late fee for A-504?')

    expect(chat.messages[1].sources).toEqual([lateFee, noticePart])
    expect(chat.messages[0].sources).toEqual([])
  })

  it('keeps a clause that two searches both returned once, and the first copy of it', async () => {
    const again = { ...lateFee, text: 'a copy of the clause' }
    script([...search([lateFee]), ...search([again, noticePart]), done])
    const chat = useChatStore()

    await chat.send('fee and notice for A-504')

    expect(chat.messages[1].sources.map((source) => source.ref)).toEqual(['LSE-A-504#5', 'LSE-A-504#4a'])
    expect(chat.messages[1].sources[0].text).toBe(lateFee.text)
  })

  it('keeps the sources of one answer apart from those of the next', async () => {
    script([...search([lateFee]), done])
    script([...search([noticePart]), done])
    const chat = useChatStore()

    await chat.send('late fee?')
    await chat.send('notice period?')

    expect(chat.messages[1].sources).toEqual([lateFee])
    expect(chat.messages[3].sources).toEqual([noticePart])
  })

  it('has no sources for a tool that sends none, or one that failed', async () => {
    script([
      { type: 'tool_call', name: 'search_units', args: {} },
      { type: 'tool_result', name: 'search_units', summary: '6 units', error: false },
      ...search(undefined, true),
      done,
    ])
    const chat = useChatStore()

    await chat.send('units')

    expect(chat.messages[1].sources).toEqual([])
  })

  it('keeps them for the whole conversation and sends only plain text back to the server as history', async () => {
    script([...search([lateFee]), { type: 'token', text: 'The fee is 3% [LSE-A-504#5].' }, done])
    script([{ type: 'token', text: 'ok' }, done])
    const chat = useChatStore()

    await chat.send('late fee for A-504?')
    await chat.send('thanks')

    expect(chat.messages[1].sources).toEqual([lateFee])
    expect(streamChat.mock.calls[1][1]).toEqual([
      { role: 'user', text: 'late fee for A-504?' },
      { role: 'assistant', text: 'The fee is 3% [LSE-A-504#5].' },
    ])
  })
})

describe('chat store proposed actions', () => {
  const action = paymentAction()
  const decided = (status: ActionView['status'], overrides: Partial<ActionView> = {}) =>
    ({ ...action, status, ...overrides }) as ActionView

  beforeEach(() => {
    setActivePinia(createPinia())
    streamChat.mockReset()
    streamConfirmation.mockReset()
    info.value = null
  })

  /** A question whose answer proposed `action`, ready for a decision. */
  async function proposed(chat: ReturnType<typeof useChatStore>) {
    script([
      { type: 'tool_call', name: 'record_payment', args: { unit_code: 'B-203', amount: 5000 } },
      { type: 'tool_result', name: 'record_payment', summary: 'Proposed, waiting for approval', error: false },
      proposal(action),
      { type: 'token', text: 'It is waiting for your approval.' },
      done,
    ])
    await chat.send('record 5000 for B-203')
  }

  it('attaches the action to the answer that proposed it, without the event type', async () => {
    const chat = useChatStore()

    await proposed(chat)

    expect(chat.messages[1].actions).toEqual([action])
    expect(chat.messages[1].actions[0]).not.toHaveProperty('type')
    expect(chat.messages[1].text).toBe('It is waiting for your approval.')
    expect(chat.messages[0].actions).toEqual([])
  })

  it('keeps several proposals of one answer in the order they arrived', async () => {
    const second = paymentAction({ id: '44444444-4444-4444-8444-444444444444' })
    script([proposal(action), proposal(second), done])
    const chat = useChatStore()

    await chat.send('record two payments')

    expect(chat.messages[1].actions.map((a) => a.id)).toEqual([action.id, second.id])
  })

  it('shows a decision in its card and asks the assistant once to confirm it, with no question bubble', async () => {
    const chat = useChatStore()
    await proposed(chat)
    scriptConfirmation([{ type: 'token', text: 'Done, SAR 5,000.00 was recorded.' }, done])

    chat.settleAction(decided('APPROVED'))
    await flushPromises()

    expect(chat.messages[1].actions[0].status).toBe('APPROVED')
    expect(streamConfirmation).toHaveBeenCalledTimes(1)
    expect(streamConfirmation.mock.calls[0][0]).toBe(action.id)
    expect(chat.messages.map((m) => m.role)).toEqual(['user', 'assistant', 'assistant'])
    expect(chat.messages[2]).toMatchObject({ text: 'Done, SAR 5,000.00 was recorded.', streaming: false })
    expect(chat.busy).toBe(false)
  })

  it('confirms the decision of an action only once, however often its card is updated', async () => {
    const chat = useChatStore()
    await proposed(chat)
    scriptConfirmation([{ type: 'token', text: 'Done.' }, done])

    chat.settleAction(decided('APPROVED'))
    chat.settleAction(decided('APPROVED'))
    await flushPromises()
    chat.settleAction(decided('APPROVED'))
    await flushPromises()

    expect(streamConfirmation).toHaveBeenCalledTimes(1)
    expect(chat.messages).toHaveLength(3)
  })

  it.each(['REJECTED', 'FAILED'] as const)('confirms a %s decision too', async (status) => {
    const chat = useChatStore()
    await proposed(chat)
    scriptConfirmation([{ type: 'token', text: 'Noted.' }, done])

    chat.settleAction(decided(status))
    await flushPromises()

    expect(streamConfirmation).toHaveBeenCalledTimes(1)
  })

  it.each([
    ['still waiting for a decision', decided('PROPOSED')],
    ['expired', decided('EXPIRED')],
    ['already confirmed in the chat', decided('APPROVED', { confirmedAt: '2026-10-10T15:05:00Z' })],
  ])('asks for no confirmation of an action that is %s, but still updates its card', async (_name, view) => {
    const chat = useChatStore()
    await proposed(chat)

    chat.settleAction(view)
    await flushPromises()

    expect(streamConfirmation).not.toHaveBeenCalled()
    expect(chat.messages[1].actions[0].status).toBe(view.status)
  })

  it('ignores an action that is in no message, such as one from a chat that was cleared', async () => {
    const chat = useChatStore()
    await proposed(chat)
    chat.clear()

    chat.settleAction(decided('APPROVED'))
    await flushPromises()

    expect(streamConfirmation).not.toHaveBeenCalled()
    expect(chat.messages).toEqual([])
  })

  it('keeps the confirmation out of the history, so the next question carries only real exchanges', async () => {
    const chat = useChatStore()
    await proposed(chat)
    scriptConfirmation([{ type: 'token', text: 'Done, it was recorded.' }, done])
    chat.settleAction(decided('APPROVED'))
    await flushPromises()
    script([{ type: 'token', text: 'ok' }, done])

    await chat.send('thanks')

    const expected = [
      { role: 'user', text: 'record 5000 for B-203' },
      { role: 'assistant', text: 'It is waiting for your approval.' },
    ]
    expect(streamConfirmation.mock.calls[0][1]).toEqual(expected)
    expect(streamChat.mock.calls[1][1]).toEqual(expected)
  })

  it('pairs every question with its own answer when a confirmation sits between exchanges', async () => {
    const chat = useChatStore()
    await proposed(chat)
    scriptConfirmation([{ type: 'token', text: 'Done, it was recorded.' }, done])
    chat.settleAction(decided('APPROVED'))
    await flushPromises()
    script([{ type: 'token', text: 'A-101 is vacant.' }, done])
    await chat.send('and unit A-101?')
    script([{ type: 'token', text: 'ok' }, done])

    await chat.send('thanks')

    expect(chat.messages.map((m) => m.text)).toEqual([
      'record 5000 for B-203',
      'It is waiting for your approval.',
      'Done, it was recorded.',
      'and unit A-101?',
      'A-101 is vacant.',
      'thanks',
      'ok',
    ])
    expect(streamChat.mock.calls[2][1]).toEqual([
      { role: 'user', text: 'record 5000 for B-203' },
      { role: 'assistant', text: 'It is waiting for your approval.' },
      { role: 'user', text: 'and unit A-101?' },
      { role: 'assistant', text: 'A-101 is vacant.' },
    ])
  })

  it('waits for the reply that is streaming and confirms the decision after it', async () => {
    const chat = useChatStore()
    await proposed(chat)
    let release: () => void = () => {}
    streamChat.mockImplementationOnce(async function* () {
      await new Promise<void>((resolve) => (release = resolve))
      yield { type: 'token', text: 'second answer' } as ChatEvent
      yield done
    })
    scriptConfirmation([{ type: 'token', text: 'Done.' }, done])

    const asking = chat.send('and unit A-101?')
    chat.settleAction(decided('APPROVED'))
    await flushPromises()
    expect(streamConfirmation).not.toHaveBeenCalled()

    release()
    await asking
    await flushPromises()

    expect(streamConfirmation).toHaveBeenCalledTimes(1)
    expect(chat.messages.map((m) => m.text)).toEqual([
      'record 5000 for B-203',
      'It is waiting for your approval.',
      'and unit A-101?',
      'second answer',
      'Done.',
    ])
    // The reply that was streaming is the only exchange the confirmation may carry as history.
    expect(streamConfirmation.mock.calls[0][1]).toEqual([
      { role: 'user', text: 'record 5000 for B-203' },
      { role: 'assistant', text: 'It is waiting for your approval.' },
      { role: 'user', text: 'and unit A-101?' },
      { role: 'assistant', text: 'second answer' },
    ])
  })

  it('drops a confirmation that could not start, because the card already shows the outcome', async () => {
    const chat = useChatStore()
    await proposed(chat)
    streamConfirmation.mockImplementationOnce(async function* () {
      throw new ApiError(409, 'Conflict', 'action_already_confirmed')
    })

    chat.settleAction(decided('APPROVED'))
    await flushPromises()

    expect(chat.messages).toHaveLength(2)
    expect(chat.busy).toBe(false)
  })

  it('keeps the error a confirmation stream reports, shown with its code', async () => {
    const chat = useChatStore()
    await proposed(chat)
    scriptConfirmation([
      { type: 'error', message: 'The AI service is busy right now.', code: 'llm_overloaded' },
      { ...done, status: 'ERROR' },
    ])

    chat.settleAction(decided('APPROVED'))
    await flushPromises()

    expect(chat.messages[2]).toMatchObject({ errorCode: 'llm_overloaded', streaming: false })
  })

  it('does not confirm after the chat was cleared while the decision waited for a reply', async () => {
    const chat = useChatStore()
    await proposed(chat)
    let release: () => void = () => {}
    streamChat.mockImplementationOnce(async function* () {
      await new Promise<void>((resolve) => (release = resolve))
      yield done
    })

    const asking = chat.send('another question')
    chat.settleAction(decided('APPROVED'))
    chat.clear()
    release()
    await asking
    await flushPromises()

    expect(streamConfirmation).not.toHaveBeenCalled()
  })
})

describe('chat store decisions', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    streamChat.mockReset()
    streamConfirmation.mockReset()
    info.value = null
  })

  it('remembers which decisions are on their way, per action, until they end', () => {
    const chat = useChatStore()

    chat.beginDecision('a1', 'approving')
    chat.beginDecision('a2', 'rejecting')

    expect(chat.deciding).toEqual({ a1: 'approving', a2: 'rejecting' })

    chat.endDecision('a1')

    expect(chat.deciding).toEqual({ a2: 'rejecting' })
  })

  it('gives an action one approval key for every attempt and another action its own', () => {
    const chat = useChatStore()

    const first = chat.approvalKey('a1')

    expect(first).toMatch(/^[0-9a-f-]{36}$/)
    expect(chat.approvalKey('a1')).toBe(first)
    expect(chat.approvalKey('a2')).not.toBe(first)
  })

  it('starts the keys over with a new chat', () => {
    const chat = useChatStore()
    const first = chat.approvalKey('a1')

    chat.clear()

    expect(chat.approvalKey('a1')).not.toBe(first)
  })
})
