import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { nextTick, ref } from 'vue'
import { i18n } from '../i18n'
import ar from '../i18n/ar'
import en from '../i18n/en'
import MessageBubble from '../components/MessageBubble.vue'
import { lateFee } from '../test/leaseFixtures'
import { useChatStore } from '../stores/chat'
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

describe('ChatView follow-ups', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    Element.prototype.scrollTo = () => {}
  })

  it('gives a message the clauses that earlier answers found, so a follow-up citation is a chip', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const chat = useChatStore()
    const message = (id: number, role: 'user' | 'assistant', text: string, sources = [] as (typeof lateFee)[]) => ({
      id, role, text, tools: [], actions: [], sources, streaming: false,
    })
    chat.messages.push(
      message(1, 'user', 'What is the late fee?'),
      message(2, 'assistant', 'The late fee is SAR 75 a day [LSE-A-504#5].', [lateFee]),
      message(3, 'user', 'And what did that clause say about the grace period?'),
      message(4, 'assistant', 'It allows 5 days of grace [LSE-A-504#5].'),
    )

    const wrapper = mount(ChatView, { global: { plugins: [pinia, i18n] } })
    await flushPromises()

    const bubbles = wrapper.findAllComponents(MessageBubble)
    expect(bubbles).toHaveLength(4)
    // The second answer has no source of its own: its chip comes from the first answer.
    expect(bubbles[3].findAll('button.cite').length).toBeGreaterThan(0)
    expect(bubbles[1].findAll('button.cite').length).toBeGreaterThan(0)
  })
})
