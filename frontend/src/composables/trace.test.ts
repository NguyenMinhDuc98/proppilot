import { describe, expect, it } from 'vitest'
import type { DonePayload } from '../api/types'
import type { ChatMessage } from '../stores/chat'
import { buildTrace } from './trace'

const usage: DonePayload = {
  runId: 'r1', status: 'OK', provider: 'anthropic', model: 'claude-haiku-4-5-20251001',
  inputTokens: 3681, outputTokens: 331, toolCalls: 1, iterations: 2, latencyMs: 4200, costUsd: 0.005859, truncated: false,
}

const message = (overrides: Partial<ChatMessage>): ChatMessage => ({
  id: 1, role: 'assistant', text: 'answer', tools: [], streaming: false, ...overrides,
})

describe('buildTrace', () => {
  it('lists call, result and answer steps with the tool arguments', () => {
    const { steps } = buildTrace(
      message({
        tools: [{ name: 'find_overdue_tenants', args: { min_days_overdue: 30 }, status: 'done', summary: '19 tenants' }],
        usage,
      }),
    )

    expect(steps).toEqual([
      { kind: 'call', main: 'find_overdue_tenants', detail: '{"min_days_overdue":30}' },
      { kind: 'result', main: '19 tenants' },
      { kind: 'answer', tokens: 331 },
    ])
  })

  it('marks a failed tool call', () => {
    const { steps } = buildTrace(
      message({ tools: [{ name: 'get_unit_details', args: {}, status: 'failed', summary: 'No unit' }], usage }),
    )

    expect(steps.map((s) => s.kind)).toEqual(['call', 'failed', 'answer'])
  })

  it('has no result step for a tool that is still running and no answer step before the run finishes', () => {
    const { steps, stats } = buildTrace(message({ tools: [{ name: 'search_units', args: {}, status: 'running' }] }))

    expect(steps.map((s) => s.kind)).toEqual(['call'])
    expect(stats).toEqual([])
  })

  it('formats the usage numbers and model name', () => {
    const { stats } = buildTrace(message({ usage }))
    const byId = Object.fromEntries(stats.map((s) => [s.id, s.value]))

    expect(byId).toEqual({
      cost: '$0.00586', latency: '4.2 s', tokensIn: '3,681', tokensOut: '331', rounds: '2', model: 'Claude Haiku 4.5',
    })
  })

  it('shows the offline assistant as free and keeps its model name', () => {
    const { stats } = buildTrace(message({ usage: { ...usage, provider: 'offline', model: 'rule-based', costUsd: 0 } }))
    const byId = Object.fromEntries(stats.map((s) => [s.id, s.value]))

    expect(byId.cost).toBe('$0')
    expect(byId.model).toBe('rule-based')
  })
})
