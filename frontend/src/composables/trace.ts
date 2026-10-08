import type { ChatMessage } from '../stores/chat'
import { formatCost, formatCount, formatLatency, formatModel } from './format'

export interface TraceStep {
  kind: 'call' | 'result' | 'failed' | 'answer'
  /** Tool name for `call`, the tool's summary for `result` and `failed`. */
  main?: string
  /** Tool arguments as compact JSON for `call`. */
  detail?: string
  /** Tokens the model wrote for `answer`. */
  tokens?: number
}

export interface TraceStat {
  id: 'cost' | 'latency' | 'tokensIn' | 'tokensOut' | 'rounds' | 'model'
  value: string
}

/**
 * Turns what the stream already told us (tool calls, tool results, usage) into the steps and numbers shown in the
 * "How I answered" drawer. Pure, so it is easy to test; wording and translation are the component's job.
 */
export function buildTrace(message: ChatMessage): { steps: TraceStep[]; stats: TraceStat[] } {
  const steps: TraceStep[] = []
  for (const tool of message.tools) {
    steps.push({ kind: 'call', main: tool.name, detail: JSON.stringify(tool.args) })
    if (tool.status === 'done') steps.push({ kind: 'result', main: tool.summary })
    if (tool.status === 'failed') steps.push({ kind: 'failed', main: tool.summary })
  }

  const usage = message.usage
  if (!usage) return { steps, stats: [] }

  steps.push({ kind: 'answer', tokens: usage.outputTokens })
  const stats: TraceStat[] = [
    { id: 'cost', value: usage.provider === 'offline' ? '$0' : formatCost(usage.costUsd) },
    { id: 'latency', value: formatLatency(usage.latencyMs) },
    { id: 'tokensIn', value: formatCount(usage.inputTokens) },
    { id: 'tokensOut', value: formatCount(usage.outputTokens) },
    { id: 'rounds', value: String(usage.iterations) },
    { id: 'model', value: usage.provider === 'offline' ? usage.model : formatModel(usage.model) },
  ]
  return { steps, stats }
}
