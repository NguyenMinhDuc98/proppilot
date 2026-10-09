import { mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import { afterEach, describe, expect, it } from 'vitest'
import type { ErrorCode } from '../api/types'
import { i18n } from '../i18n'
import ar from '../i18n/ar'
import en from '../i18n/en'
import type { ChatMessage } from '../stores/chat'
import MessageBubble from './MessageBubble.vue'

const errorCodes: ErrorCode[] = ['llm_overloaded', 'llm_timeout', 'llm_auth', 'llm_error', 'run_timeout']

function render(overrides: Partial<ChatMessage>) {
  const message: ChatMessage = { id: 1, role: 'assistant', text: '', tools: [], streaming: false, ...overrides }
  return mount(MessageBubble, { props: { message }, global: { plugins: [i18n] } })
}

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
