import { mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import { afterEach, describe, expect, it } from 'vitest'
import { i18n } from '../i18n'
import ar from '../i18n/ar'
import en from '../i18n/en'
import type { ChatMessage } from '../stores/chat'
import TraceDrawer from './TraceDrawer.vue'

const message: ChatMessage = {
  id: 1,
  role: 'assistant',
  text: 'There are 6 units',
  preamble: 'Let me check the units.',
  tools: [{ name: 'search_units', args: {}, status: 'done', summary: '6 units found' }],
  streaming: false,
}

afterEach(() => {
  i18n.global.locale.value = 'en'
})

describe('TraceDrawer', () => {
  it('lists what the model wrote before its tool calls as the first step, in both languages', async () => {
    const wrapper = mount(TraceDrawer, { props: { message }, global: { plugins: [i18n] } })
    const firstStep = () => wrapper.get('.step')

    expect(firstStep().text()).toContain(en.trace.note)
    expect(firstStep().text()).toContain('Let me check the units.')

    i18n.global.locale.value = 'ar'
    await nextTick()
    expect(firstStep().text()).toContain(ar.trace.note)
  })
})
