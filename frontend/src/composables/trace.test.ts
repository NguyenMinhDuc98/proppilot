import { describe, expect, it } from 'vitest'
import type { DonePayload } from '../api/types'
import type { ChatMessage } from '../stores/chat'
import { adminRunDetail, llmCall, runTrace, SONNET, toolCall } from '../test/traceFixtures'
import { adminToolDetails, buildStoredTrace, buildTrace, toolDetailsOf } from './trace'

const usage: DonePayload = {
  runId: 'r1', status: 'OK', provider: 'anthropic', model: 'claude-haiku-4-5-20251001', routedReason: 'DEFAULT',
  guardrailStatus: 'OK',
  inputTokens: 3681, outputTokens: 331, cacheReadTokens: 0, cacheWriteTokens: 0, toolCalls: 1, iterations: 2,
  latencyMs: 4200, costUsd: 0.005859, truncated: false,
}

const message = (overrides: Partial<ChatMessage>): ChatMessage => ({
  id: 1, role: 'assistant', text: 'answer', tools: [], actions: [], sources: [], streaming: false, ...overrides,
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

  it('starts with a note holding what the model wrote before its tool calls', () => {
    const { steps } = buildTrace(
      message({
        preamble: 'Let me check the units.',
        tools: [{ name: 'search_units', args: {}, status: 'done', summary: '6 units' }],
        usage,
      }),
    )

    expect(steps.map((s) => s.kind)).toEqual(['note', 'call', 'result', 'answer'])
    expect(steps[0]).toEqual({ kind: 'note', main: 'Let me check the units.' })
  })

  it('has no note step when the model wrote nothing before its tool calls', () => {
    const { steps } = buildTrace(
      message({ tools: [{ name: 'search_units', args: {}, status: 'done', summary: '6 units' }], usage }),
    )

    expect(steps.map((s) => s.kind)).toEqual(['call', 'result', 'answer'])
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

  it('counts what the prompt cache served and took in as tokens in, and shows each part apart', () => {
    const cached = { ...usage, inputTokens: 12, cacheReadTokens: 3400, cacheWriteTokens: 269 }

    const { stats } = buildTrace(message({ usage: cached }))
    const byId = Object.fromEntries(stats.map((s) => [s.id, s.value]))

    expect(byId.tokensIn).toBe('3,681')
    expect(byId.tokensCached).toBe('3,400')
    expect(byId.tokensCacheWritten).toBe('269')
    expect(stats.map((s) => s.id)).toEqual([
      'cost', 'latency', 'tokensIn', 'tokensCached', 'tokensCacheWritten', 'tokensOut', 'rounds', 'model',
    ])
  })

  it('counts a cache write as input too, and has no cached stat when nothing was read from the cache', () => {
    const written = { ...usage, inputTokens: 12, cacheWriteTokens: 3669 }

    const { stats } = buildTrace(message({ usage: written }))

    expect(stats.find((s) => s.id === 'tokensIn')?.value).toBe('3,681')
    expect(stats.map((s) => s.id)).not.toContain('tokensCached')
  })

  it('shows the offline assistant as free and keeps its model name', () => {
    const { stats } = buildTrace(message({ usage: { ...usage, provider: 'offline', model: 'rule-based', costUsd: 0 } }))
    const byId = Object.fromEntries(stats.map((s) => [s.id, s.value]))

    expect(byId.cost).toBe('$0')
    expect(byId.model).toBe('rule-based')
  })
})

describe('buildTrace routing and guardrail', () => {
  it('names why the answer came from another model, and says nothing for the default one', () => {
    expect(buildTrace(message({ usage: { ...usage, routedReason: 'COMPLEX' } })).routing).toBe('COMPLEX')
    expect(buildTrace(message({ usage: { ...usage, routedReason: 'FALLBACK' } })).routing).toBe('FALLBACK')
    expect(buildTrace(message({ usage })).routing).toBeNull()
  })

  it('carries the verdict of the guardrail, or none for an answer it did not check', () => {
    expect(buildTrace(message({ usage: { ...usage, guardrailStatus: 'RETRIED_FLAGGED' } })).guardrail)
      .toBe('RETRIED_FLAGGED')
    expect(buildTrace(message({ usage: { ...usage, guardrailStatus: null } })).guardrail).toBeNull()
  })
})

describe('buildStoredTrace', () => {
  const stepKinds = (trace: ReturnType<typeof buildStoredTrace>) => trace.steps.map((step) => step.kind)

  it('puts each tool after the model call that asked for it, in the order they happened', () => {
    const trace = runTrace({
      llmCalls: [
        llmCall({ seq: 1 }),
        llmCall({ seq: 2, stopReason: 'TOOL_USE' }),
        llmCall({ seq: 3, stopReason: 'END_TURN' }),
      ],
      toolCalls: [
        toolCall({ seq: 1, llmSeq: 1, name: 'search_units' }),
        toolCall({ seq: 2, llmSeq: 1, name: 'get_unit_details' }),
        toolCall({ seq: 3, llmSeq: 2, name: 'find_overdue_tenants' }),
      ],
    })
    const details = new Map([
      [1, { args: '{"city":"Riyadh"}', summary: '6 units found' }],
      [2, { args: '{"unit_code":"A-203"}', summary: 'A-203, occupied' }],
      [3, { args: '{}', summary: '4 tenants' }],
    ])

    const { steps } = buildStoredTrace(trace, details)

    expect(steps.map((step) => (step.kind === 'model' ? `model ${step.model?.seq}` : `${step.kind} ${step.main}`))).toEqual([
      'model 1',
      'call search_units', 'result 6 units found',
      'call get_unit_details', 'result A-203, occupied',
      'model 2',
      'call find_overdue_tenants', 'result 4 tenants',
      'model 3',
    ])
    expect(steps[1]).toMatchObject({ detail: '{"city":"Riyadh"}', latencyMs: 25 })
  })

  it('lists a tool the stored trace knows only by name, with how long it took', () => {
    const { steps } = buildStoredTrace(runTrace())

    expect(steps.map((step) => step.kind)).toEqual(['model', 'call', 'model'])
    expect(steps[1]).toMatchObject({ kind: 'call', main: 'search_units', latencyMs: 25 })
  })

  it('marks a tool that failed, with its message when it is known', () => {
    const trace = runTrace({ toolCalls: [toolCall({ error: true })] })

    expect(stepKinds(buildStoredTrace(trace))).toEqual(['model', 'call', 'failed', 'model'])
    const { steps } = buildStoredTrace(trace, new Map([[1, { args: '{}', summary: 'No unit matches A-999' }]]))
    expect(steps[2]).toEqual({ kind: 'failed', main: 'No unit matches A-999' })
  })

  it('shows what each call read and wrote, and what the cache read and wrote', () => {
    const { steps } = buildStoredTrace(runTrace())

    expect(steps[0].model).toEqual({
      seq: 1, name: 'Claude Haiku 4.5', tokensIn: 4200, tokensOut: 45, cacheRead: 0, cacheWrite: 3000,
      latencyMs: 640, stopReason: 'TOOL_USE', errorCode: null,
    })
    expect(steps[2].model).toMatchObject({ tokensIn: 3040, cacheRead: 3000, cacheWrite: 0, stopReason: 'END_TURN' })
  })

  it('totals the run from the stored figures, with the cache tokens apart', () => {
    const { stats } = buildStoredTrace(runTrace())

    expect(Object.fromEntries(stats.map((stat) => [stat.id, stat.value]))).toEqual({
      cost: '$0.00123',
      latency: '1.4 s',
      tokensIn: '7,240',
      tokensCached: '3,000',
      tokensCacheWritten: '3,000',
      tokensOut: '205',
      rounds: '2',
      model: 'Claude Haiku 4.5',
    })
  })

  it('has no cache stats for a run the cache did not touch', () => {
    const trace = runTrace()
    trace.totals = { ...trace.totals, cacheReadTokens: 0, cacheWriteTokens: 0 }

    const { stats } = buildStoredTrace(trace)

    expect(stats.map((stat) => stat.id)).toEqual(['cost', 'latency', 'tokensIn', 'tokensOut', 'rounds', 'model'])
  })

  it.each(['COMPLEX', 'GUARDRAIL_RETRY', 'FALLBACK'] as const)('names %s as the reason for the model', (reason) => {
    expect(buildStoredTrace(runTrace({ routedReason: reason })).routing).toBe(reason)
  })

  it('has no reason to give for the default model or for a run stored before routing', () => {
    expect(buildStoredTrace(runTrace({ routedReason: 'DEFAULT' })).routing).toBeNull()
    expect(buildStoredTrace(runTrace({ routedReason: null })).routing).toBeNull()
  })

  it('shows a model call that failed and the call on the other model that answered in its place', () => {
    const trace = runTrace({
      model: SONNET,
      routedReason: 'FALLBACK',
      llmCalls: [
        llmCall({
          seq: 1, inputTokens: 0, outputTokens: 0, latencyMs: 5200, stopReason: null, outcome: 'ERROR',
          errorCode: 'llm_overloaded',
        }),
        llmCall({ seq: 2, model: SONNET, stopReason: 'END_TURN' }),
      ],
      toolCalls: [],
    })

    const built = buildStoredTrace(trace)

    expect(built.steps.map((step) => step.model?.errorCode ?? null)).toEqual(['llm_overloaded', null])
    expect(built.steps.map((step) => step.model?.name)).toEqual(['Claude Haiku 4.5', 'Claude Sonnet 4.5'])
    expect(built.steps[0].model).toMatchObject({ stopReason: null, latencyMs: 5200 })
    expect(built.routing).toBe('FALLBACK')
    expect(Object.fromEntries(built.stats.map((stat) => [stat.id, stat.value])).model).toBe('Claude Sonnet 4.5')
  })

  it('names a failed call without a code as a failure of the service', () => {
    const trace = runTrace({
      llmCalls: [llmCall({ stopReason: null, outcome: 'ERROR', errorCode: null })],
      toolCalls: [],
    })

    expect(buildStoredTrace(trace).steps[0].model?.errorCode).toBe('llm_error')
  })

  it('lists tools that are not tied to a model call after the calls', () => {
    const trace = runTrace({ toolCalls: [toolCall({ llmSeq: null }), toolCall({ seq: 2, llmSeq: 9, name: 'get_unit_details' })] })

    expect(buildStoredTrace(trace).steps.map((step) => step.main ?? step.kind)).toEqual([
      'model', 'model', 'search_units', 'get_unit_details',
    ])
  })

  it('puts what the model wrote before its tools after the first call that asked for them', () => {
    const { steps } = buildStoredTrace(runTrace(), new Map(), 'Let me check the units.')

    expect(steps.map((step) => step.kind)).toEqual(['model', 'note', 'call', 'model'])
    expect(steps[1]).toEqual({ kind: 'note', main: 'Let me check the units.' })
  })

  it('carries the verdict of the guardrail and how the question scored', () => {
    const complexity = { score: 3, rules: ['SEVERAL_DATA_AREAS', 'SEQUENCING_WORD'] }

    const built = buildStoredTrace(runTrace({ guardrail: { status: 'RETRIED_FLAGGED', violations: 2 }, complexity }))

    expect(built.guardrail).toBe('RETRIED_FLAGGED')
    expect(built.complexity).toEqual(complexity)
  })

  it('shows the offline assistant as free and keeps its model name', () => {
    const trace = runTrace({
      provider: 'offline',
      model: 'rule-based',
      llmCalls: [llmCall({ model: 'rule-based' }), llmCall({ seq: 2, model: 'rule-based', stopReason: 'END_TURN' })],
    })
    trace.totals = { ...trace.totals, costUsd: 0, cacheReadTokens: 0, cacheWriteTokens: 0 }

    const built = buildStoredTrace(trace)

    expect(built.steps[0].model?.name).toBe('rule-based')
    expect(Object.fromEntries(built.stats.map((stat) => [stat.id, stat.value]))).toMatchObject({
      cost: '$0', model: 'rule-based',
    })
  })
})

describe('toolDetailsOf', () => {
  it('takes what each tool was asked and said from the answer, by the order the server ran them', () => {
    const trace = runTrace({
      toolCalls: [toolCall({ seq: 1, name: 'search_units' }), toolCall({ seq: 2, name: 'get_unit_details' })],
    })
    const shown = message({
      tools: [
        { name: 'search_units', args: { city: 'Riyadh' }, status: 'done', summary: '6 units found' },
        { name: 'get_unit_details', args: {}, status: 'failed', summary: 'No such unit' },
      ],
    })

    expect(toolDetailsOf(shown, trace)).toEqual(new Map([
      [1, { args: '{"city":"Riyadh"}', summary: '6 units found' }],
      [2, { args: '{}', summary: 'No such unit' }],
    ]))
  })

  it('leaves out a tool whose name is not the one the answer shows, so no result lands under another tool', () => {
    const trace = runTrace({ toolCalls: [toolCall({ seq: 1, name: 'search_units' }), toolCall({ seq: 2, name: 'find_overdue_tenants' })] })
    const shown = message({
      tools: [{ name: 'get_occupancy_summary', args: {}, status: 'done', summary: 'x' }],
    })

    expect(toolDetailsOf(shown, trace).size).toBe(0)
  })
})

describe('adminToolDetails', () => {
  it('joins what each tool was asked and said to the tool by its sequence number', () => {
    const detail = adminRunDetail({
      toolCallDetails: [{ seq: 2, args: '{"unit_code":"A-203"}', summary: 'A-203' }],
    })

    expect(adminToolDetails(detail)).toEqual(new Map([[2, { args: '{"unit_code":"A-203"}', summary: 'A-203' }]]))
  })
})
