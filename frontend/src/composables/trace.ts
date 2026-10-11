import type {
  AdminRunDetail, GuardrailStatus, RoutedReason, RunLlmCall, RunToolCall, RunTrace, StopReason,
} from '../api/types'
import type { ChatMessage } from '../stores/chat'
import { formatCost, formatCount, formatLatency, formatModel } from './format'
import { totalInputTokens } from './usage'

/** One call to a model in a stored trace, as the timeline shows it. */
export interface ModelStep {
  seq: number
  name: string
  /** Everything the call read: what the cache did not serve, plus what it served and what it took in. */
  tokensIn: number
  tokensOut: number
  cacheRead: number
  cacheWrite: number
  latencyMs: number
  stopReason: StopReason | null
  /** Why the call failed; null for one that was answered. */
  errorCode: string | null
}

export interface TraceStep {
  kind: 'note' | 'model' | 'call' | 'result' | 'failed' | 'answer'
  /** The model's text for `note`, the tool name for `call`, the tool's summary for `result` and `failed`. */
  main?: string
  /** Tool arguments as compact JSON for `call`. */
  detail?: string
  /** Tokens the model wrote for `answer`. */
  tokens?: number
  /** How long the tool took, for `call`. */
  latencyMs?: number
  /** The model call, for `model`. */
  model?: ModelStep
}

export interface TraceStat {
  id: 'cost' | 'latency' | 'tokensIn' | 'tokensCached' | 'tokensCacheWritten' | 'tokensOut' | 'rounds' | 'model'
  value: string
}

export interface Trace {
  steps: TraceStep[]
  stats: TraceStat[]
  /** Why the model that answered was not simply the default one; null when it was, or when nobody said. */
  routing: Exclude<RoutedReason, 'DEFAULT'> | null
  guardrail: GuardrailStatus | null
  /** How the question scored, with the names of the rules it met; null when it was not scored. */
  complexity: { score: number; rules: string[] } | null
}

/** What a tool was asked and said, for a stored trace, which holds only that it ran. */
export interface ToolDetail {
  args: string
  summary: string
}

/** The figures the stats are made of, whether they come from the stream or from a stored trace. */
interface Figures {
  model: string
  costUsd: number
  latencyMs: number
  inputTokens: number
  outputTokens: number
  cacheReadTokens: number
  cacheWriteTokens: number
  iterations: number
}

/** The default model needs no explaining, and a run stored before routing has no reason to give. */
const unusualRouting = (reason: RoutedReason | null) => (reason && reason !== 'DEFAULT' ? reason : null)

function statsOf(figures: Figures): TraceStat[] {
  const stats: TraceStat[] = [
    { id: 'cost', value: formatCost(figures.costUsd) },
    { id: 'latency', value: formatLatency(figures.latencyMs) },
    { id: 'tokensIn', value: formatCount(totalInputTokens(figures)) },
  ]
  // Only when the cache did something: a run that was not cached has nothing to explain.
  if (figures.cacheReadTokens > 0) stats.push({ id: 'tokensCached', value: formatCount(figures.cacheReadTokens) })
  if (figures.cacheWriteTokens > 0) stats.push({ id: 'tokensCacheWritten', value: formatCount(figures.cacheWriteTokens) })
  stats.push(
    { id: 'tokensOut', value: formatCount(figures.outputTokens) },
    { id: 'rounds', value: String(figures.iterations) },
    { id: 'model', value: formatModel(figures.model) },
  )
  return stats
}

/**
 * Turns what the stream already told us (tool calls, tool results, usage) into the steps and numbers shown in the
 * "How I answered" drawer. Pure, so it is easy to test; wording and translation are the component's job. It is what
 * the drawer shows until the stored trace of the run arrives, or when there is none.
 */
export function buildTrace(message: ChatMessage): Trace {
  const steps: TraceStep[] = []
  if (message.preamble) steps.push({ kind: 'note', main: message.preamble })
  for (const tool of message.tools) {
    steps.push({ kind: 'call', main: tool.name, detail: JSON.stringify(tool.args) })
    if (tool.status === 'done') steps.push({ kind: 'result', main: tool.summary })
    if (tool.status === 'failed') steps.push({ kind: 'failed', main: tool.summary })
  }

  const usage = message.usage
  if (!usage) return { steps, stats: [], routing: null, guardrail: null, complexity: null }

  steps.push({ kind: 'answer', tokens: usage.outputTokens })
  return {
    steps,
    stats: statsOf(usage),
    routing: unusualRouting(usage.routedReason),
    guardrail: usage.guardrailStatus,
    complexity: null,
  }
}

const modelStep = (call: RunLlmCall): ModelStep => ({
  seq: call.seq,
  name: formatModel(call.model),
  tokensIn: totalInputTokens(call),
  tokensOut: call.outputTokens,
  cacheRead: call.cacheReadTokens,
  cacheWrite: call.cacheWriteTokens,
  latencyMs: call.latencyMs,
  stopReason: call.stopReason,
  errorCode: call.outcome === 'ERROR' ? (call.errorCode ?? 'llm_error') : null,
})

/** A tool's steps: the call, then what it returned or why it failed when something is known of that. */
function toolSteps(call: RunToolCall, detail: ToolDetail | undefined): TraceStep[] {
  const steps: TraceStep[] = [{ kind: 'call', main: call.name, detail: detail?.args, latencyMs: call.latencyMs }]
  if (detail?.summary || call.error) steps.push({ kind: call.error ? 'failed' : 'result', main: detail?.summary })
  return steps
}

/**
 * The timeline of a stored trace: each model call followed by the tools it asked for, in the order they happened.
 * The public trace says only that a tool ran, so what a tool was asked and said comes from `details` by the tool's
 * `seq`: the stream has it for the person who asked, and the admin's detail of the run has it for the owner.
 *
 * @param note what the model wrote before its first tool call, which the stored trace does not hold
 */
export function buildStoredTrace(
  trace: RunTrace,
  details: ReadonlyMap<number, ToolDetail> = new Map(),
  note?: string,
): Trace {
  const steps: TraceStep[] = []
  const known = new Set(trace.llmCalls.map((call) => call.seq))
  const asked = (call: RunLlmCall) => trace.toolCalls.filter((tool) => tool.llmSeq === call.seq)

  let pendingNote = note
  for (const call of trace.llmCalls) {
    steps.push({ kind: 'model', model: modelStep(call) })
    const tools = asked(call)
    if (pendingNote && tools.length > 0) {
      steps.push({ kind: 'note', main: pendingNote })
      pendingNote = undefined
    }
    for (const tool of tools) steps.push(...toolSteps(tool, details.get(tool.seq)))
  }
  // Tools the server did not tie to a model call (a run stored before it did) follow the calls.
  for (const tool of trace.toolCalls) {
    if (tool.llmSeq === null || !known.has(tool.llmSeq)) steps.push(...toolSteps(tool, details.get(tool.seq)))
  }

  const { totals } = trace
  return {
    steps,
    stats: statsOf({
      model: trace.model,
      costUsd: totals.costUsd,
      latencyMs: totals.latencyMs,
      inputTokens: totals.inputTokens,
      outputTokens: totals.outputTokens,
      cacheReadTokens: totals.cacheReadTokens,
      cacheWriteTokens: totals.cacheWriteTokens,
      iterations: totals.iterations,
    }),
    routing: unusualRouting(trace.routedReason),
    guardrail: trace.guardrail.status,
    complexity: trace.complexity,
  }
}

/**
 * What the person's own answer knows of each tool of a stored trace: the stream carried the arguments and the result
 * line of every tool call, in the order the server ran them, so the n-th tool is the n-th call. A call is matched
 * only when the names agree, so a trace that does not fit the stream cannot put a result under the wrong tool.
 */
export function toolDetailsOf(message: ChatMessage, trace: RunTrace): Map<number, ToolDetail> {
  const details = new Map<number, ToolDetail>()
  for (const call of trace.toolCalls) {
    const shown = message.tools[call.seq - 1]
    if (shown && shown.name === call.name) {
      details.set(call.seq, { args: JSON.stringify(shown.args), summary: shown.summary ?? '' })
    }
  }
  return details
}

/** What the admin's detail of a run holds of each tool, which the public trace leaves out. */
export function adminToolDetails(detail: AdminRunDetail): Map<number, ToolDetail> {
  return new Map(detail.toolCallDetails.map(({ seq, args, summary }) => [seq, { args, summary }]))
}
