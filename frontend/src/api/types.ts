export interface DonePayload {
  runId: string
  status: 'OK' | 'ERROR' | 'MAX_ITERATIONS'
  provider: string
  model: string
  inputTokens: number
  outputTokens: number
  toolCalls: number
  iterations: number
  latencyMs: number
  costUsd: number
}

export type ChatEvent =
  | { type: 'tool_call'; name: string; args: Record<string, unknown> }
  | { type: 'tool_result'; name: string; summary: string; error: boolean }
  | { type: 'token'; text: string }
  | { type: 'error'; message: string }
  | ({ type: 'done' } & DonePayload)

export interface HistoryTurn {
  role: 'user' | 'assistant'
  text: string
}

export interface Unit {
  code: string
  building: string
  buildingCode: string
  cityEn: string
  cityAr: string
  floor: number
  bedrooms: number
  areaSqm: number
  monthlyRent: number
  status: 'VACANT' | 'OCCUPIED' | 'MAINTENANCE'
}

export interface UnitPage {
  items: Unit[]
  total: number
  page: number
  size: number
}

export interface UnitQuery {
  city?: string
  building?: string
  status?: string
  bedrooms?: number
  minRent?: number
  maxRent?: number
  page: number
  size: number
}

export interface Stats {
  totalRuns: number
  totalCostUsd: number
  avgCostUsd: number
  avgLatencyMs: number
  avgToolCalls: number
  totalInputTokens: number
  totalOutputTokens: number
  toolUsage: { tool: string; calls: number }[]
  recentRuns: { at: string; question: string; toolCalls: number; latencyMs: number; costUsd: number; status: string }[]
}
