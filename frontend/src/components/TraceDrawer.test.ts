import { flushPromises, mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { DonePayload } from '../api/types'
import { i18n } from '../i18n'
import ar from '../i18n/ar'
import en from '../i18n/en'
import type { ChatMessage } from '../stores/chat'
import { llmCall, runTrace, SONNET, toolCall } from '../test/traceFixtures'
import TraceDrawer from './TraceDrawer.vue'

const fetchRunTrace = vi.fn()
vi.mock('../api/runs', () => ({ fetchRunTrace: (...args: unknown[]) => fetchRunTrace(...args) }))

const usage: DonePayload = {
  runId: 'run-1', status: 'OK', provider: 'anthropic', model: 'claude-haiku-4-5-20251001', routedReason: 'DEFAULT',
  guardrailStatus: 'OK', inputTokens: 1240, outputTokens: 205, cacheReadTokens: 3000, cacheWriteTokens: 3000,
  toolCalls: 1, iterations: 2, latencyMs: 1400, costUsd: 0.001234, truncated: false,
}

const message: ChatMessage = {
  id: 1,
  role: 'assistant',
  text: 'There are 6 units',
  preamble: 'Let me check the units.',
  tools: [{ name: 'search_units', args: { city: 'Riyadh' }, status: 'done', summary: '6 units found' }],
  actions: [],
  sources: [],
  streaming: false,
}

const answered: ChatMessage = { ...message, usage }

async function render(shown: ChatMessage = answered) {
  const wrapper = mount(TraceDrawer, { props: { message: shown }, global: { plugins: [i18n] } })
  await flushPromises()
  return wrapper
}

const kinds = (wrapper: Awaited<ReturnType<typeof render>>) =>
  wrapper.findAll('.step').map((step) => step.classes().find((name) => name !== 'step' && name !== 'problem'))

beforeEach(() => {
  fetchRunTrace.mockReset()
})

afterEach(() => {
  i18n.global.locale.value = 'en'
})

describe('TraceDrawer from the stream', () => {
  it('lists what the model wrote before its tool calls as the first step, in both languages', async () => {
    const wrapper = await render({ ...message, usage: undefined })
    const firstStep = () => wrapper.get('.step')

    expect(firstStep().text()).toContain(en.trace.note)
    expect(firstStep().text()).toContain('Let me check the units.')

    i18n.global.locale.value = 'ar'
    await nextTick()
    expect(firstStep().text()).toContain(ar.trace.note)
  })

  it('reads no trace for an answer that has no run', async () => {
    await render({ ...message, usage: undefined })

    expect(fetchRunTrace).not.toHaveBeenCalled()
  })
})

describe('TraceDrawer from the stored trace', () => {
  it('reads the trace of the run once the answer is complete', async () => {
    fetchRunTrace.mockResolvedValue(runTrace())

    await render()

    expect(fetchRunTrace).toHaveBeenCalledTimes(1)
    expect(fetchRunTrace).toHaveBeenCalledWith('run-1')
  })

  it('shows each model call with the tools it asked for, with their arguments and results from the answer', async () => {
    fetchRunTrace.mockResolvedValue(runTrace())

    const wrapper = await render()

    expect(kinds(wrapper)).toEqual(['model', 'note', 'call', 'result', 'model'])
    const steps = wrapper.findAll('.step')
    expect(steps[0].text()).toContain('Model call 1')
    expect(steps[0].text()).toContain('Claude Haiku 4.5')
    expect(steps[0].text()).toContain('4,200 in · 45 out')
    expect(steps[0].text()).toContain('640 ms')
    expect(steps[0].text()).toContain(en.trace.stop.TOOL_USE)
    expect(steps[1].text()).toContain('Let me check the units.')
    expect(steps[2].text()).toContain('search_units')
    expect(steps[2].text()).toContain('{"city":"Riyadh"}')
    expect(steps[2].text()).toContain('25 ms')
    expect(steps[3].text()).toContain('6 units found')
    expect(steps[4].text()).toContain('Model call 2')
    expect(steps[4].text()).toContain(en.trace.stop.END_TURN)
  })

  it('shows the cache tokens of a call when there are some, and says nothing when there are none', async () => {
    fetchRunTrace.mockResolvedValue(runTrace())

    const wrapper = await render()

    const [first, , , , second] = wrapper.findAll('.step')
    expect(first.text()).toContain('3,000 written to the cache')
    expect(first.text()).not.toContain('read from the cache')
    expect(second.text()).toContain('3,000 read from the cache')
    expect(second.text()).not.toContain('written to the cache')
  })

  it('shows the totals of the stored run with the cache tokens apart', async () => {
    fetchRunTrace.mockResolvedValue(runTrace())

    const wrapper = await render()

    const stats = Object.fromEntries(wrapper.findAll('.stat').map((stat) => [stat.get('dt').text(), stat.get('dd').text()]))
    expect(stats).toEqual({
      [en.trace.stats.cost]: '$0.00123',
      [en.trace.stats.latency]: '1.4 s',
      [en.trace.stats.tokensIn]: '7,240',
      [en.trace.stats.tokensCached]: '3,000',
      [en.trace.stats.tokensCacheWritten]: '3,000',
      [en.trace.stats.tokensOut]: '205',
      [en.trace.stats.rounds]: '2',
      [en.trace.stats.model]: 'Claude Haiku 4.5',
    })
  })

  it.each([
    ['COMPLEX', en.trace.routed.COMPLEX, ar.trace.routed.COMPLEX],
    ['GUARDRAIL_RETRY', en.trace.routed.GUARDRAIL_RETRY, ar.trace.routed.GUARDRAIL_RETRY],
    ['FALLBACK', en.trace.routed.FALLBACK, ar.trace.routed.FALLBACK],
  ] as const)('explains in both languages why the model was %s', async (reason, english, arabic) => {
    fetchRunTrace.mockResolvedValue(runTrace({ routedReason: reason, model: SONNET }))

    const wrapper = await render()

    expect(wrapper.get('.chip').text()).toBe(english)
    i18n.global.locale.value = 'ar'
    await nextTick()
    expect(wrapper.get('.chip').text()).toBe(arabic)
  })

  it('has no routing chip for the default model', async () => {
    fetchRunTrace.mockResolvedValue(runTrace({ routedReason: 'DEFAULT' }))

    const wrapper = await render()

    expect(wrapper.find('.chip').exists()).toBe(false)
  })

  it('shows the model call that failed before the fallback model answered', async () => {
    fetchRunTrace.mockResolvedValue(runTrace({
      model: SONNET,
      routedReason: 'FALLBACK',
      llmCalls: [
        llmCall({ stopReason: null, outcome: 'ERROR', errorCode: 'llm_overloaded', inputTokens: 0, outputTokens: 0, latencyMs: 5200 }),
        llmCall({ seq: 2, model: SONNET, stopReason: 'END_TURN' }),
      ],
      toolCalls: [],
    }))

    const wrapper = await render({ ...answered, tools: [] })

    const steps = wrapper.findAll('.step')
    expect(steps[0].text()).toContain('Model call 1 failed')
    expect(steps[0].text()).toContain(en.chat.errors.llm_overloaded)
    expect(steps[0].classes()).toContain('problem')
    expect(steps[1].text()).toContain('Claude Sonnet 4.5')
    expect(wrapper.get('.chip').text()).toBe(en.trace.routed.FALLBACK)
  })

  it('says what the guardrail concluded', async () => {
    fetchRunTrace.mockResolvedValue(runTrace({ guardrail: { status: 'RETRIED_OK', violations: 0 } }))

    const wrapper = await render()

    expect(wrapper.get('.check').text()).toContain(en.trace.checks.RETRIED_OK)
  })

  it('explains the complexity score with the rules the question met', async () => {
    fetchRunTrace.mockResolvedValue(runTrace({
      routedReason: 'COMPLEX', complexity: { score: 3, rules: ['SEVERAL_DATA_AREAS', 'SEQUENCING_WORD'] },
    }))

    const wrapper = await render()

    expect(wrapper.get('.complexity').text()).toBe(
      'Question complexity: 3 (more than one area of data, a word that asks for steps)',
    )
  })

  it('opens and closes the panel with the toggle', async () => {
    fetchRunTrace.mockResolvedValue(runTrace())
    const wrapper = mount(TraceDrawer, { props: { message: answered }, global: { plugins: [i18n] }, attachTo: document.body })
    await flushPromises()
    const toggle = wrapper.get('button.toggle')

    expect(toggle.attributes('aria-expanded')).toBe('false')
    expect(wrapper.get('.panel').isVisible()).toBe(false)

    await toggle.trigger('click')

    expect(toggle.attributes('aria-expanded')).toBe('true')
    expect(wrapper.get('.panel').isVisible()).toBe(true)
    wrapper.unmount()
  })
})

describe('TraceDrawer when the stored trace is not there', () => {
  it('shows the trace built from the stream when the request fails', async () => {
    fetchRunTrace.mockRejectedValue(new Error('network'))

    const wrapper = await render()

    expect(kinds(wrapper)).toEqual(['note', 'call', 'result', 'answer'])
    expect(wrapper.text()).toContain('search_units')
    expect(wrapper.text()).toContain('6 units found')
    expect(wrapper.text()).not.toContain('Model call')
  })

  it('shows the trace built from the stream for a run stored without its calls', async () => {
    fetchRunTrace.mockResolvedValue(runTrace({ llmCalls: [], toolCalls: [] }))

    const wrapper = await render()

    expect(kinds(wrapper)).toEqual(['note', 'call', 'result', 'answer'])
  })

  it('does not wait for the request: the stream trace is there while it is on its way', async () => {
    fetchRunTrace.mockReturnValue(new Promise(() => {}))

    const wrapper = mount(TraceDrawer, { props: { message: answered }, global: { plugins: [i18n] } })
    await nextTick()

    expect(kinds(wrapper)).toEqual(['note', 'call', 'result', 'answer'])
    expect(wrapper.find('.stat').exists()).toBe(true)
  })

  it('keeps the routing and guardrail verdict that the stream carried', async () => {
    fetchRunTrace.mockRejectedValue(new Error('network'))

    const wrapper = await render({ ...answered, usage: { ...usage, routedReason: 'COMPLEX', guardrailStatus: 'FLAGGED' } })

    expect(wrapper.get('.chip').text()).toBe(en.trace.routed.COMPLEX)
    expect(wrapper.get('.check').text()).toContain(en.trace.checks.FLAGGED)
  })
})

describe('TraceDrawer when the stored trace does not fit the answer', () => {
  it('leaves out the arguments of a tool whose name differs, rather than showing them under another tool', async () => {
    fetchRunTrace.mockResolvedValue(runTrace({ toolCalls: [toolCall({ name: 'find_overdue_tenants' })] }))

    const wrapper = await render()

    const call = wrapper.findAll('.step').find((step) => step.classes().includes('call'))
    expect(call?.text()).toContain('find_overdue_tenants')
    expect(call?.text()).not.toContain('Riyadh')
    expect(wrapper.text()).not.toContain('6 units found')
  })
})
