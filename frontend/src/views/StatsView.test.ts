import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Stats } from '../api/types'
import { nextTick } from 'vue'
import { i18n } from '../i18n'
import ar from '../i18n/ar'
import en from '../i18n/en'
import StatsView from './StatsView.vue'

const fetchStats = vi.fn()
vi.mock('../api/units', () => ({ fetchStats: () => fetchStats() }))

const stats: Stats = {
  totalRuns: 42,
  totalCostUsd: 0.25,
  avgCostUsd: 0.006,
  avgLatencyMs: 4200,
  avgToolCalls: 1.2,
  totalInputTokens: 9000,
  totalOutputTokens: 1500,
  toolUsage: [{ tool: 'search_units', calls: 30 }],
  byModel: [
    { model: 'claude-haiku-4-5-20251001', runs: 36, costUsd: 0.18 },
    { model: 'claude-sonnet-4-5', runs: 6, costUsd: 0.07 },
  ],
  cache: { readTokens: 5000, writeTokens: 4000 },
  guardrail: { flaggedRuns: 3 },
}

describe('StatsView', () => {
  beforeEach(() => {
    fetchStats.mockReset()
  })

  afterEach(() => {
    i18n.global.locale.value = 'en'
  })

  async function render(shown: Partial<Stats> = {}) {
    fetchStats.mockResolvedValue({ ...stats, ...shown })
    const wrapper = mount(StatsView, { global: { plugins: [i18n] } })
    await flushPromises()
    return wrapper
  }

  const section = (wrapper: Awaited<ReturnType<typeof render>>, id: string) => wrapper.get(`section[aria-labelledby="${id}"]`)

  it('shows the aggregates and the tool usage', async () => {
    fetchStats.mockResolvedValue(stats)

    const wrapper = mount(StatsView, { global: { plugins: [i18n] } })
    await flushPromises()

    expect(wrapper.text()).toContain('42')
    expect(wrapper.text()).toContain('4.2 s')
    expect(wrapper.text()).toContain(en.tools.search_units.done)
  })

  describe('tokens in and out', () => {
    const tile = (wrapper: Awaited<ReturnType<typeof render>>) =>
      wrapper.findAll('.tile').find((candidate) => candidate.text().startsWith(en.stats.tokens))

    it('counts what the prompt cache served and took in with the input, as the cache section below does', async () => {
      const wrapper = await render()

      // 9,000 uncached, 5,000 read from the cache and 4,000 written to it
      expect(tile(wrapper)?.text()).toBe(`${en.stats.tokens}18,000 / 1,500`)
    })

    it('shows the uncached input alone when the server reports no cache', async () => {
      const { cache: _cache, ...older } = stats
      fetchStats.mockResolvedValue(older)
      const wrapper = mount(StatsView, { global: { plugins: [i18n] } })
      await flushPromises()

      expect(tile(wrapper)?.text()).toBe(`${en.stats.tokens}9,000 / 1,500`)
    })
  })

  it('never lists what visitors asked, even when the server sends the questions', async () => {
    fetchStats.mockResolvedValue({
      ...stats,
      recentRuns: [{ at: '2026-10-09T08:00:00Z', question: 'Is Ahmed Al-Harbi late on rent?', toolCalls: 1 }],
    })

    const wrapper = mount(StatsView, { global: { plugins: [i18n] } })
    await flushPromises()

    expect(wrapper.text()).not.toContain('Ahmed Al-Harbi')
    expect(wrapper.find('table').exists()).toBe(false)
  })

  describe('model mix', () => {
    it('lists the questions and the cost of each model that answered, with the model named as in the trace', async () => {
      const wrapper = await render()

      const rows = section(wrapper, 'stats-models').findAll('.model-row:not(.head)').map((row) => row.text())
      expect(rows).toEqual(['Claude Haiku 4.536$0.180', 'Claude Sonnet 4.56$0.070'])
    })

    it('names the offline assistant as it calls itself', async () => {
      const wrapper = await render({ byModel: [{ model: 'rule-based', runs: 12, costUsd: 0 }] })

      expect(section(wrapper, 'stats-models').text()).toContain('rule-based')
      expect(section(wrapper, 'stats-models').text()).toContain('$0')
    })

    it('says so when no model has answered', async () => {
      const wrapper = await render({ byModel: [] })

      expect(section(wrapper, 'stats-models').text()).toContain(en.stats.modelsNone)
      expect(section(wrapper, 'stats-models').find('.model-row').exists()).toBe(false)
    })
  })

  describe('prompt cache', () => {
    it('shows the tokens read from and written to the cache and the share of the input it served', async () => {
      const wrapper = await render()

      const figures = section(wrapper, 'stats-cache').findAll('.figure').map((figure) => figure.text())
      expect(figures).toEqual([
        `${en.stats.cacheRead}5,000`,
        `${en.stats.cacheWrite}4,000`,
        // 5,000 of 18,000: 9,000 uncached, 5,000 read from the cache and 4,000 written to it
        `${en.stats.cacheShare}28%`,
      ])
    })

    it('does not claim a share when the cache served nothing', async () => {
      const wrapper = await render({ cache: { readTokens: 0, writeTokens: 800 } })

      const figures = section(wrapper, 'stats-cache').findAll('.figure').map((figure) => figure.text())
      expect(figures).toEqual([`${en.stats.cacheRead}0`, `${en.stats.cacheWrite}800`])
    })

    it('explains that nothing was cached, and why that can be, when both counts are zero', async () => {
      const wrapper = await render({ cache: { readTokens: 0, writeTokens: 0 } })

      expect(section(wrapper, 'stats-cache').text()).toContain(en.stats.cacheNone)
      expect(section(wrapper, 'stats-cache').find('.figure').exists()).toBe(false)
    })
  })

  describe('flagged answers', () => {
    it('shows how many answers had figures that could not be checked', async () => {
      const wrapper = await render()

      expect(section(wrapper, 'stats-flagged').get('.figure strong').text()).toBe('3')
      expect(section(wrapper, 'stats-flagged').text()).toContain(en.stats.flaggedSome)
    })

    it('says that none was flagged when the count is zero', async () => {
      const wrapper = await render({ guardrail: { flaggedRuns: 0 } })

      expect(section(wrapper, 'stats-flagged').text()).toContain(en.stats.flaggedNone)
      expect(section(wrapper, 'stats-flagged').find('.figure').exists()).toBe(false)
    })
  })

  it('shows the new sections in Arabic too', async () => {
    const wrapper = await render()

    i18n.global.locale.value = 'ar'
    await nextTick()

    expect(section(wrapper, 'stats-models').get('h2').text()).toBe(ar.stats.models)
    expect(section(wrapper, 'stats-cache').get('h2').text()).toBe(ar.stats.cache)
    expect(section(wrapper, 'stats-flagged').get('h2').text()).toBe(ar.stats.flagged)
  })

  it('still shows the page when the server is older and sends none of the new figures', async () => {
    const { byModel: _models, cache: _cache, guardrail: _guardrail, ...older } = stats
    fetchStats.mockResolvedValue(older)

    const wrapper = mount(StatsView, { global: { plugins: [i18n] } })
    await flushPromises()

    expect(wrapper.text()).toContain('42')
    expect(section(wrapper, 'stats-models').text()).toContain(en.stats.modelsNone)
    expect(section(wrapper, 'stats-cache').text()).toContain(en.stats.cacheNone)
    expect(section(wrapper, 'stats-flagged').text()).toContain(en.stats.flaggedNone)
  })
})
