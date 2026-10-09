import { flushPromises, mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { AppInfo } from '../api/types'
import ar from '../i18n/ar'
import en from '../i18n/en'

const fetchInfo = vi.fn()
vi.mock('../api/info', () => ({ fetchInfo: () => fetchInfo() }))

const offline: AppInfo = { mode: 'offline', provider: 'offline', model: 'rule-based' }
const claude: AppInfo = { mode: 'claude', provider: 'anthropic', model: 'claude-haiku-4-5-20251001' }

// The info is cached at module level, so each test loads fresh copies of the component and the i18n instance.
async function mountBadge(reply: () => Promise<AppInfo>) {
  fetchInfo.mockImplementation(reply)
  vi.resetModules()
  const { default: ModeBadge } = await import('./ModeBadge.vue')
  const { i18n } = await import('../i18n')
  return { wrapper: mount(ModeBadge, { global: { plugins: [i18n] } }), i18n }
}

describe('ModeBadge', () => {
  beforeEach(() => {
    fetchInfo.mockReset()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('shows nothing until the mode is known, then says the demo is offline', async () => {
    let answer: (info: AppInfo) => void = () => {}
    const { wrapper } = await mountBadge(() => new Promise((resolve) => (answer = resolve)))
    expect(wrapper.text()).toBe('')

    answer(offline)
    await flushPromises()

    expect(wrapper.text()).toBe(en.app.offlineDemo)
  })

  it('names the Claude model with its short name', async () => {
    const { wrapper } = await mountBadge(() => Promise.resolve(claude))

    await flushPromises()

    expect(wrapper.text()).toBe('Claude Haiku 4.5')
  })

  it('shows the offline text in Arabic', async () => {
    const { wrapper, i18n } = await mountBadge(() => Promise.resolve(offline))
    await flushPromises()

    i18n.global.locale.value = 'ar'
    await nextTick()

    expect(wrapper.text()).toBe(ar.app.offlineDemo)
  })

  it('stays hidden when the info cannot be loaded', async () => {
    const { wrapper } = await mountBadge(() => Promise.reject(new Error('network')))

    await flushPromises()

    expect(wrapper.find('.badge').exists()).toBe(false)
  })

  it('appears once a retry reaches a backend that was still waking up', async () => {
    vi.useFakeTimers()
    let requests = 0
    const { wrapper } = await mountBadge(() =>
      requests++ === 0 ? Promise.reject(new Error('server asleep')) : Promise.resolve(claude),
    )
    await vi.advanceTimersByTimeAsync(0)
    expect(wrapper.find('.badge').exists()).toBe(false)

    await vi.advanceTimersByTimeAsync(10_000)

    expect(wrapper.text()).toBe('Claude Haiku 4.5')
  })
})
