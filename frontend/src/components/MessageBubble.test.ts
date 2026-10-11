import { flushPromises, mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { DonePayload, ErrorCode } from '../api/types'
import { i18n } from '../i18n'
import ar from '../i18n/ar'
import en from '../i18n/en'
import type { ChatMessage, ToolChip } from '../stores/chat'
import MessageBubble from './MessageBubble.vue'

// The trace drawer asks for the stored trace of a run; these tests are about the bubble, so it is not there.
const fetchRunTrace = vi.fn()
vi.mock('../api/runs', () => ({ fetchRunTrace: (...args: unknown[]) => fetchRunTrace(...args) }))

const errorCodes: ErrorCode[] = ['llm_overloaded', 'llm_timeout', 'llm_auth', 'llm_error', 'run_timeout']

function render(overrides: Partial<ChatMessage>) {
  const message: ChatMessage = { id: 1, role: 'assistant', text: '', tools: [], actions: [], sources: [], streaming: false, ...overrides }
  return mount(MessageBubble, { props: { message }, global: { plugins: [i18n] } })
}

const usage: DonePayload = {
  runId: 'r1', status: 'OK', provider: 'anthropic', model: 'claude-haiku-4-5-20251001', routedReason: 'DEFAULT',
  guardrailStatus: 'OK',
  inputTokens: 100, outputTokens: 2048, cacheReadTokens: 0, cacheWriteTokens: 0, toolCalls: 0, iterations: 1,
  latencyMs: 9000, costUsd: 0.01, truncated: false,
}

const toolChip = (name: string, status: ToolChip['status'] = 'done'): ToolChip => ({ name, args: {}, status })

beforeEach(() => {
  fetchRunTrace.mockReset()
  fetchRunTrace.mockRejectedValue(new Error('no trace in this test'))
})

afterEach(() => {
  i18n.global.locale.value = 'en'
})

describe('MessageBubble errors', () => {
  it.each(errorCodes)('shows localised text for %s instead of the server message', async (code) => {
    const wrapper = render({ error: 'raw upstream text', errorCode: code })

    expect(wrapper.get('[role="alert"]').text()).toBe(en.chat.errors[code])

    i18n.global.locale.value = 'ar'
    await nextTick()
    expect(wrapper.get('[role="alert"]').text()).toBe(ar.chat.errors[code])
    expect(wrapper.text()).not.toContain('raw upstream text')
  })

  it('falls back to the generic message for a code it does not know', () => {
    const wrapper = render({ error: 'raw upstream text', errorCode: 'something_new' as ErrorCode })

    expect(wrapper.get('[role="alert"]').text()).toBe(en.chat.errors.generic)
  })

  it('keeps the dedicated text for a network failure and the server detail for an HTTP error', () => {
    expect(render({ error: 'network' }).get('[role="alert"]').text()).toBe(en.chat.errorNetwork)
    expect(render({ error: 'Too many questions' }).get('[role="alert"]').text())
      .toBe(`${en.chat.errorPrefix}: Too many questions`)
  })
})

describe('MessageBubble truncated answers', () => {
  it('tells the reader the answer was cut off, in both languages', async () => {
    const wrapper = render({ text: 'The portfolio has 150 un', usage: { ...usage, status: 'TRUNCATED', truncated: true } })

    expect(wrapper.get('[role="note"]').text()).toBe(en.chat.truncated)

    i18n.global.locale.value = 'ar'
    await nextTick()
    expect(wrapper.get('[role="note"]').text()).toBe(ar.chat.truncated)
  })

  it('shows no notice for a complete answer or while streaming', () => {
    expect(render({ text: 'All done', usage }).find('[role="note"]').exists()).toBe(false)
    expect(render({ text: 'Partial', streaming: true }).find('[role="note"]').exists()).toBe(false)
  })
})

describe('MessageBubble guardrail', () => {
  const flagged: NonNullable<ChatMessage['guardrail']> = {
    status: 'FLAGGED',
    values: [
      { kind: 'NUMBER', value: '151' },
      { kind: 'UNIT_CODE', value: 'A-999' },
      { kind: 'NUMBER', value: '151' },
    ],
  }

  it('warns under an answer with values it could not match to the data, and lists each once', async () => {
    const wrapper = render({ text: 'The portfolio has 151 units; A-999 is vacant.', guardrail: flagged, usage })

    expect(wrapper.get('[role="status"]').text()).toBe(en.guardrail.flagged)
    const listed = wrapper.findAll('.guardrail li').map((item) => item.text())
    expect(listed).toEqual([`${en.guardrail.kinds.NUMBER}151`, `${en.guardrail.kinds.UNIT_CODE}A-999`])

    i18n.global.locale.value = 'ar'
    await nextTick()
    expect(wrapper.get('[role="status"]').text()).toBe(ar.guardrail.flagged)
    expect(wrapper.findAll('.guardrail li').map((item) => item.text())).toEqual([
      `${ar.guardrail.kinds.NUMBER}151`,
      `${ar.guardrail.kinds.UNIT_CODE}A-999`,
    ])
  })

  it('notes that an answer was checked again and now matches, without a warning', () => {
    const wrapper = render({ text: 'Answer', guardrail: { status: 'RETRIED_OK', values: [] }, usage })

    expect(wrapper.get('.rechecked').text()).toBe(en.guardrail.rechecked)
    expect(wrapper.find('.badge').exists()).toBe(false)
  })

  it('shows nothing for an answer that was checked and fine, or not checked, or is still streaming', () => {
    expect(render({ text: 'Answer', guardrail: { status: 'OK', values: [] }, usage }).find('.guardrail, .rechecked').exists()).toBe(false)
    expect(render({ text: 'Answer', usage }).find('.guardrail, .rechecked').exists()).toBe(false)
    expect(render({ text: 'Partial', guardrail: flagged, streaming: true }).find('.guardrail, .rechecked').exists()).toBe(false)
  })

  it('shows the "How I answered" trace for a flagged answer even when no tool was called', () => {
    const wrapper = render({ text: 'The portfolio has 151 units.', guardrail: flagged, usage })

    expect(wrapper.find('.trace').exists()).toBe(true)
  })

  it.each(['RETRIED_OK', 'RETRIED_FLAGGED'] as const)(
    'shows the trace of an answer that was asked for again and is %s, even when no tool was called',
    (status) => {
      const wrapper = render({ text: 'Answer', guardrail: { status, values: [] }, usage })

      expect(wrapper.find('.trace').exists()).toBe(true)
    },
  )

  it('shows no trace for an answer that called no tool and was not flagged', () => {
    expect(render({ text: 'Hello', usage }).find('.trace').exists()).toBe(false)
  })

  it('shows no trace, and asks for none, for a checked answer with nothing to explain and no tool called', async () => {
    const wrapper = render({ text: 'Hello', guardrail: { status: 'OK', values: [] }, usage })
    await flushPromises()

    expect(wrapper.find('.trace').exists()).toBe(false)
    expect(fetchRunTrace).not.toHaveBeenCalled()
  })

  it('shows the trace of a checked answer that called a tool', async () => {
    const wrapper = render({
      text: 'Answer', guardrail: { status: 'OK', values: [] }, tools: [toolChip('search_units')], usage,
    })
    await flushPromises()

    expect(wrapper.find('.trace').exists()).toBe(true)
    expect(fetchRunTrace).toHaveBeenCalledWith('r1')
  })

  it('says the figures are being checked again while the answer to replace has been dropped', async () => {
    const replaced = {}
    const wrapper = render({ text: '', streaming: true, replaced, tools: [toolChip('get_occupancy_summary')] })

    expect(wrapper.get('[role="status"]').text()).toBe(en.chat.rechecking)

    i18n.global.locale.value = 'ar'
    await nextTick()
    expect(wrapper.get('[role="status"]').text()).toBe(ar.chat.rechecking)
  })

  it('stops saying so once the new answer is streaming, and when nothing was asked again', () => {
    const replaced = {}

    expect(render({ text: 'The portfolio has 150 units.', streaming: true, replaced }).find('[role="status"]').exists()).toBe(false)
    expect(render({ text: '', streaming: true }).text()).not.toContain(en.chat.rechecking)
  })
})

describe('MessageBubble usage line', () => {
  it('counts the tokens the prompt cache served and took in with the others', () => {
    const cached = { ...usage, inputTokens: 12, outputTokens: 300, cacheReadTokens: 3400, cacheWriteTokens: 269 }

    const wrapper = render({ text: 'Answer', usage: cached })

    expect(wrapper.get('.meta').text()).toContain('3,981 tokens')
  })
})

describe('MessageBubble data sources', () => {
  const sources = (wrapper: ReturnType<typeof render>) => wrapper.find('.sources')
  const toolNames = [
    'search_units', 'get_unit_details', 'find_overdue_tenants',
    'get_occupancy_summary', 'get_payment_history', 'draft_tenant_message',
    'record_payment', 'create_maintenance_request', 'send_tenant_reminder',
    'search_lease_documents', 'find_expiring_leases',
  ] as const

  it.each(toolNames)('names %s with a friendly label in both languages instead of the tool name', async (name) => {
    const wrapper = render({ text: 'Answer', tools: [toolChip(name)], usage })

    expect(sources(wrapper).text()).toBe(`Data from: ${en.tools[name].source}`)
    expect(sources(wrapper).text()).not.toContain(name)

    i18n.global.locale.value = 'ar'
    await nextTick()
    expect(sources(wrapper).text()).toBe(`البيانات من: ${ar.tools[name].source}`)
  })

  it('lists each tool once however often it was called', () => {
    const tools = [toolChip('search_units'), toolChip('find_overdue_tenants'), toolChip('search_units')]
    const wrapper = render({ text: 'Answer', tools, usage })

    expect(sources(wrapper).text()).toBe('Data from: Unit search and Overdue rent check')
  })

  it('falls back to the raw name for a tool it has no label for', () => {
    const wrapper = render({ text: 'Answer', tools: [toolChip('brand_new_tool')], usage })

    expect(sources(wrapper).text()).toBe('Data from: brand_new_tool')
  })

  it('leaves out a tool that failed', () => {
    const wrapper = render({ text: 'Answer', tools: [toolChip('search_units', 'failed'), toolChip('get_unit_details')], usage })

    expect(sources(wrapper).text()).toBe('Data from: Unit details')
    expect(render({ text: 'Answer', tools: [toolChip('search_units', 'failed')], usage }).find('.sources').exists()).toBe(false)
  })

  it('shows no sources while streaming, without tools or without an answer', () => {
    expect(sources(render({ text: 'Partial', tools: [toolChip('search_units')], streaming: true })).exists()).toBe(false)
    expect(sources(render({ text: 'Answer', usage })).exists()).toBe(false)
    expect(sources(render({ text: '', tools: [toolChip('search_units')], error: 'network' })).exists()).toBe(false)
  })
})

describe('MessageBubble tool chips', () => {
  const proposingTools = ['record_payment', 'create_maintenance_request', 'send_tenant_reminder'] as const
  const chips = (wrapper: ReturnType<typeof render>) => wrapper.findAll('.chip').map((chip) => chip.text())

  it.each(proposingTools)('labels %s while it runs and when it is done, in both languages', async (name) => {
    const running = render({ streaming: true, tools: [toolChip(name, 'running')] })
    const finished = render({ text: 'Proposed.', tools: [toolChip(name)], usage })

    expect(chips(running)).toEqual([en.tools[name].running])
    expect(chips(finished)).toEqual([en.tools[name].done])

    i18n.global.locale.value = 'ar'
    await nextTick()
    expect(chips(running)).toEqual([ar.tools[name].running])
    expect(chips(finished)).toEqual([ar.tools[name].done])
  })

  const leaseTools = ['search_lease_documents', 'find_expiring_leases'] as const

  it.each(leaseTools)('labels %s while it runs, when it is done and when it failed, in both languages', async (name) => {
    const running = render({ streaming: true, tools: [toolChip(name, 'running')] })
    const finished = render({ text: 'Found.', tools: [toolChip(name)], usage })
    const failed = render({ text: 'It could not be searched.', tools: [toolChip(name, 'failed')], usage })

    expect(chips(running)).toEqual([en.tools[name].running])
    expect(chips(finished)).toEqual([en.tools[name].done])
    expect(chips(failed)).toEqual([en.tools[name].failed])
    expect(en.tools[name].running).not.toBe(en.tools.unknown.running)

    i18n.global.locale.value = 'ar'
    await nextTick()
    expect(chips(running)).toEqual([ar.tools[name].running])
    expect(chips(finished)).toEqual([ar.tools[name].done])
    expect(chips(failed)).toEqual([ar.tools[name].failed])
    expect(ar.tools[name].running).not.toBe(ar.tools.unknown.running)
  })

  it.each(proposingTools)('does not call a refused %s a prepared change, in either language', async (name) => {
    const wrapper = render({ text: 'It could not be proposed.', tools: [toolChip(name, 'failed')], usage })

    expect(chips(wrapper)).toEqual([en.tools[name].failed])
    expect(en.tools[name].failed).not.toBe(en.tools[name].done)

    i18n.global.locale.value = 'ar'
    await nextTick()
    expect(chips(wrapper)).toEqual([ar.tools[name].failed])
    expect(ar.tools[name].failed).not.toBe(ar.tools[name].done)
  })

  it('words a failed read tool and an unknown one by their own failed label', () => {
    expect(chips(render({ tools: [toolChip('search_units', 'failed')] }))).toEqual([en.tools.search_units.failed])
    expect(chips(render({ tools: [toolChip('brand_new_tool', 'failed')] }))).toEqual([en.tools.unknown.failed])
  })

  it('falls back to a neutral label for a tool it does not know', () => {
    expect(chips(render({ tools: [toolChip('brand_new_tool', 'running')], streaming: true }))).toEqual([en.tools.unknown.running])
  })
})
