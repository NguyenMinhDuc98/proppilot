import { mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import { afterEach, describe, expect, it } from 'vitest'
import type { DonePayload, ErrorCode } from '../api/types'
import { i18n } from '../i18n'
import ar from '../i18n/ar'
import en from '../i18n/en'
import type { ChatMessage, ToolChip } from '../stores/chat'
import MessageBubble from './MessageBubble.vue'

const errorCodes: ErrorCode[] = ['llm_overloaded', 'llm_timeout', 'llm_auth', 'llm_error', 'run_timeout']

function render(overrides: Partial<ChatMessage>) {
  const message: ChatMessage = { id: 1, role: 'assistant', text: '', tools: [], streaming: false, ...overrides }
  return mount(MessageBubble, { props: { message }, global: { plugins: [i18n] } })
}

const usage: DonePayload = {
  runId: 'r1', status: 'OK', provider: 'anthropic', model: 'claude-haiku-4-5-20251001',
  inputTokens: 100, outputTokens: 2048, toolCalls: 0, iterations: 1, latencyMs: 9000, costUsd: 0.01, truncated: false,
}

const toolChip = (name: string, status: ToolChip['status'] = 'done'): ToolChip => ({ name, args: {}, status })

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

describe('MessageBubble data sources', () => {
  const sources = (wrapper: ReturnType<typeof render>) => wrapper.find('.sources')
  const toolNames = [
    'search_units', 'get_unit_details', 'find_overdue_tenants',
    'get_occupancy_summary', 'get_payment_history', 'draft_tenant_message',
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
