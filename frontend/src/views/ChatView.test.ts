import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { nextTick, ref } from 'vue'
import { i18n } from '../i18n'
import ar from '../i18n/ar'
import en from '../i18n/en'
import ChatView from './ChatView.vue'

const streamChat = vi.fn()
vi.mock('../api/chat', () => ({
  streamChat: (...args: unknown[]) => streamChat(...args),
  streamConfirmation: vi.fn(),
}))
vi.mock('../composables/useAppInfo', () => ({ useAppInfo: () => ({ info: ref(null) }) }))

function render() {
  return mount(ChatView, { global: { plugins: [createPinia(), i18n] } })
}

const examples = (wrapper: ReturnType<typeof render>) => wrapper.findAll('.example').map((example) => example.text())

describe('ChatView examples', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    streamChat.mockReset()
    // jsdom does not scroll, and the thread scrolls to the newest message.
    Element.prototype.scrollTo = () => {}
  })

  afterEach(() => {
    i18n.global.locale.value = 'en'
  })

  it('offers a question about a seeded lease and one about the leases that end soon, in English', () => {
    const shown = examples(render())

    expect(shown).toEqual(en.chat.examples)
    expect(shown).toContain('What is the late fee in the lease for unit A-203?')
    expect(shown).toContain('Which leases end in the next 60 days?')
  })

  it('offers the same two questions in Arabic', async () => {
    const wrapper = render()
    i18n.global.locale.value = 'ar'
    await nextTick()

    const shown = examples(wrapper)
    expect(shown).toEqual(ar.chat.examples)
    expect(shown).toContain('ما هي غرامة التأخير في عقد الوحدة A-203؟')
    expect(shown).toContain('ما العقود التي تنتهي خلال 60 يومًا؟')
    expect(shown).toHaveLength(en.chat.examples.length)
  })

  it('isolates a unit code in an example from the writing direction and keeps it on one line', async () => {
    const wrapper = render()
    i18n.global.locale.value = 'ar'
    await nextTick()

    const codes = wrapper.findAll('.example bdi.unit-code').map((code) => code.text())

    expect(codes).toEqual(['B-203', 'A-203'])
  })

  it('asks the assistant the example that was clicked', async () => {
    streamChat.mockImplementationOnce(async function* () {})
    const wrapper = render()
    const lease = wrapper.findAll('.example').find((example) => example.text().includes('A-203'))

    await lease?.trigger('click')
    await flushPromises()

    expect(streamChat.mock.calls[0][0]).toBe('What is the late fee in the lease for unit A-203?')
  })
})
