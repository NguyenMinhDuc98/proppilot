import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { Stats } from '../api/types'
import { i18n } from '../i18n'
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
}

describe('StatsView', () => {
  beforeEach(() => {
    fetchStats.mockReset()
  })

  it('shows the aggregates and the tool usage', async () => {
    fetchStats.mockResolvedValue(stats)

    const wrapper = mount(StatsView, { global: { plugins: [i18n] } })
    await flushPromises()

    expect(wrapper.text()).toContain('42')
    expect(wrapper.text()).toContain('4.2 s')
    expect(wrapper.text()).toContain(en.tools.search_units.done)
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
})
