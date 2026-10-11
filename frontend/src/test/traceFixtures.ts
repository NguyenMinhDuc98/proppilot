import type { AdminRun, AdminRunDetail, RunLlmCall, RunToolCall, RunTrace } from '../api/types'

export const HAIKU = 'claude-haiku-4-5-20251001'
export const SONNET = 'claude-sonnet-4-5'

/** A model call that was answered: the first of a run that went on to ask for tools. */
export function llmCall(overrides: Partial<RunLlmCall> = {}): RunLlmCall {
  return {
    seq: 1,
    model: HAIKU,
    inputTokens: 1200,
    outputTokens: 45,
    cacheReadTokens: 0,
    cacheWriteTokens: 0,
    latencyMs: 640,
    stopReason: 'TOOL_USE',
    outcome: 'OK',
    errorCode: null,
    startedAt: '2026-10-11T08:00:00Z',
    ...overrides,
  }
}

export function toolCall(overrides: Partial<RunToolCall> = {}): RunToolCall {
  return { seq: 1, llmSeq: 1, name: 'search_units', error: false, latencyMs: 25, ...overrides }
}

/** A run that asked for one tool and then answered: two model calls, the second one served by the cache. */
export function runTrace(overrides: Partial<RunTrace> = {}): RunTrace {
  return {
    runId: 'a1b2c3d4-0000-4000-8000-000000000001',
    createdAt: '2026-10-11T08:00:02Z',
    status: 'OK',
    provider: 'anthropic',
    model: HAIKU,
    routedReason: 'DEFAULT',
    complexity: null,
    guardrail: { status: 'OK', violations: 0 },
    totals: {
      inputTokens: 1240,
      outputTokens: 205,
      cacheReadTokens: 3000,
      cacheWriteTokens: 3000,
      costUsd: 0.001234,
      latencyMs: 1400,
      iterations: 2,
      toolCalls: 1,
    },
    llmCalls: [
      llmCall({ cacheWriteTokens: 3000 }),
      llmCall({
        seq: 2, inputTokens: 40, outputTokens: 160, cacheReadTokens: 3000, latencyMs: 710, stopReason: 'END_TURN',
        startedAt: '2026-10-11T08:00:00.700Z',
      }),
    ],
    toolCalls: [toolCall()],
    ...overrides,
  }
}

export function adminRun(overrides: Partial<AdminRun> = {}): AdminRun {
  return {
    id: 'a1b2c3d4-0000-4000-8000-000000000001',
    createdAt: '2026-10-11T08:00:02Z',
    question: 'Which units in Riyadh are vacant right now?',
    status: 'OK',
    provider: 'anthropic',
    model: HAIKU,
    routedReason: 'DEFAULT',
    guardrailStatus: 'OK',
    guardrailViolations: 0,
    toolNames: ['search_units'],
    iterations: 2,
    inputTokens: 1240,
    outputTokens: 205,
    cacheReadTokens: 3000,
    cacheWriteTokens: 3000,
    costUsd: 0.001234,
    latencyMs: 1400,
    ...overrides,
  }
}

export function adminRunDetail(overrides: Partial<AdminRunDetail> = {}): AdminRunDetail {
  return {
    ...runTrace(),
    question: 'Which units in Riyadh are vacant right now?',
    toolCallDetails: [{ seq: 1, args: '{"city":"Riyadh","status":"VACANT"}', summary: '6 units found' }],
    guardrailFindings: [],
    ...overrides,
  }
}
