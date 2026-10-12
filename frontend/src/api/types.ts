/** What the grounding guardrail concluded about the answer; null when it did not check it. */
export type GuardrailStatus = 'OK' | 'FLAGGED' | 'RETRIED_OK' | 'RETRIED_FLAGGED'

/** Why the model that answered was chosen: the default one, or something sent the run to the other. */
export type RoutedReason = 'DEFAULT' | 'COMPLEX' | 'GUARDRAIL_RETRY' | 'FALLBACK'

/** How a run ended. ABORTED is a run whose browser left; it is recorded but never reported to a client. */
export type RunStatus = 'OK' | 'ERROR' | 'MAX_ITERATIONS' | 'TRUNCATED' | 'ABORTED'

/** A value of an answer that nothing the assistant was given supports: a figure, a unit code, a tenant name or a citation. */
export interface UnverifiedValue {
  kind: 'NUMBER' | 'UNIT_CODE' | 'TENANT_NAME' | 'CITATION'
  /** As the answer wrote it, cut to 40 characters. */
  value: string
}

export interface DonePayload {
  runId: string
  status: Exclude<RunStatus, 'ABORTED'>
  provider: string
  /** The model that gave the answer. */
  model: string
  /** Why that model was chosen. */
  routedReason: RoutedReason
  guardrailStatus: GuardrailStatus | null
  /** Only the input tokens that were neither read from nor written to the prompt cache: add the two below for all. */
  inputTokens: number
  outputTokens: number
  /** Input tokens the prompt cache served, at a tenth of the input price. */
  cacheReadTokens: number
  /** Input tokens that were written to the prompt cache, at a little more than the input price. */
  cacheWriteTokens: number
  toolCalls: number
  iterations: number
  latencyMs: number
  costUsd: number
  /** The answer hit the model's length limit and was cut off. */
  truncated: boolean
}

/** Why a run failed; the UI shows its own localised text for each, never the server's message. */
export type ErrorCode = 'llm_overloaded' | 'llm_timeout' | 'llm_auth' | 'llm_error' | 'run_timeout'

/** A lease clause an answer rests on. The server sends the clauses a lease search found with its result. */
export interface LeaseSource {
  /** What a citation in the answer is written as without its brackets, such as `LSE-A-504#7`. */
  ref: string
  documentCode: string
  documentTitle: string
  unitCode: string
  tenantNameEn: string
  tenantNameAr: string
  /** The language the lease document is written in. */
  language: 'en' | 'ar'
  /** Null for the preamble, which has no numbered clause. */
  sectionNo: number | null
  sectionTitle: string | null
  page: number
  text: string
  /** Where the PDF of the document is served, on the API's own origin. */
  pdfPath: string
}

export type ChatEvent =
  | { type: 'tool_call'; name: string; args: Record<string, unknown> }
  /** `sources` is there for the result of a lease search only. */
  | { type: 'tool_result'; name: string; summary: string; error: boolean; sources?: LeaseSource[] }
  | ({ type: 'action_proposed' } & ActionView)
  | { type: 'token'; text: string }
  /**
   * The answer streamed so far is to be dropped: the assistant was asked to answer again, and a new answer follows.
   * When it never completes, this comes once more and the earlier answer follows again.
   */
  | { type: 'revise'; reason: 'grounding' }
  | { type: 'error'; message: string; code: ErrorCode }
  /** Sent before `done`, only for an answer that states values nothing supports. */
  | { type: 'guardrail'; status: 'unverified'; values: UnverifiedValue[] }
  | ({ type: 'done' } & DonePayload)

export interface HistoryTurn {
  role: 'user' | 'assistant'
  text: string
}

/** Which assistant the backend runs (real Claude, or the free built-in offline one) and the history limits it enforces. */
export interface AppInfo {
  mode: 'claude' | 'offline'
  provider: string
  model: string
  /** The most turns the server accepts as history. */
  maxHistoryItems: number
  /** The most characters the server accepts in one history turn. */
  maxHistoryTurnLength: number
  /** Whether this deployment can be reset to its seed data, so the page may say that it is. */
  demoReset?: boolean
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
  /** The input tokens that were neither read from nor written to the prompt cache. */
  totalInputTokens: number
  totalOutputTokens: number
  toolUsage: { tool: string; calls: number }[]
  /** The runs and their cost by the model that answered. */
  byModel: { model: string; runs: number; costUsd: number }[]
  /** What the prompt cache served and took in over all runs: zero where the API never cached a prompt. */
  cache: { readTokens: number; writeTokens: number }
  guardrail: { flaggedRuns: number }
}

export type ActionKind = 'RECORD_PAYMENT' | 'CREATE_MAINTENANCE_REQUEST' | 'SEND_TENANT_REMINDER'

/** PROPOSED is the only state that can still change. */
export type ActionStatus = 'PROPOSED' | 'APPROVED' | 'REJECTED' | 'EXPIRED' | 'FAILED'

/** What a card shows: the stored status, or the decision that is on its way to the server. */
export type ActionState = ActionStatus | 'APPROVING' | 'REJECTING'

export type PaymentMethod = 'CASH' | 'BANK_TRANSFER' | 'CARD' | 'CHEQUE'
export type MaintenanceCategory = 'PLUMBING' | 'ELECTRICAL' | 'HVAC' | 'APPLIANCE' | 'STRUCTURAL' | 'CLEANING' | 'OTHER'
export type MaintenancePriority = 'LOW' | 'MEDIUM' | 'HIGH' | 'URGENT'
export type ReminderPurpose = 'RENT_REMINDER' | 'LEASE_RENEWAL' | 'MAINTENANCE_NOTICE'

/** One monthly invoice a payment would settle, in whole or in part. Amounts are SAR. */
export interface PaymentSlice {
  paymentId: number
  /** First day of the month the invoice is for, like 2026-03-01. */
  period: string
  amount: number
  paidBefore: number
  applied: number
  paidAfter: number
  remainingAfter: number
  status: 'PAID' | 'PARTIAL'
}

export interface PaymentPreview {
  unitCode: string
  tenantEn: string
  tenantAr: string
  amount: number
  paymentDate: string
  method: PaymentMethod
  outstandingBefore: number
  outstandingAfter: number
  /** The part of the amount that is more than what is owed; it is not applied to any invoice. */
  overpaymentCredit: number
  payments: PaymentSlice[]
}

export interface MaintenancePreview {
  unitCode: string
  building: string
  category: MaintenanceCategory
  priority: MaintenancePriority
  description: string
  status: string
}

export interface ReminderPreview {
  unitCode: string
  tenantEn: string
  tenantAr: string
  purpose: ReminderPurpose
  language: 'EN' | 'AR'
  subject: string
  body: string
  delivered: boolean
}

export interface PaymentArgs {
  unit_code: string
  amount: number
  payment_date: string
  method: PaymentMethod
  overridePayment: boolean
}

export interface PaymentResult {
  unitCode: string
  amount: number
  applied: number
  overpaymentCredit: number
  outstandingAfter: number
}

export interface MaintenanceResult {
  requestId: number
  unitCode: string
  status: string
}

/** The message as the outbox stored it, written again when the reminder was approved. */
export interface ReminderResult {
  messageId: string
  unitCode: string
  tenantEn: string
  tenantAr: string
  purpose: ReminderPurpose
  /** The outbox keeps the language in lower case, unlike the preview. */
  language: 'en' | 'ar'
  subject: string
  body: string
  delivered: boolean
}

/** What a FAILED action holds where an approved one holds its result. `message` is an English fallback. */
export interface ActionFailure {
  code: string
  message: string
}

interface ActionBase {
  id: string
  status: ActionStatus
  summaryEn: string
  summaryAr: string
  createdAt: string
  expiresAt: string
  decidedAt: string | null
  /** The rejection reason a person typed, or why an approval failed (English, from the server). */
  decisionReason: string | null
  /** When the assistant confirmed the decision in the chat; null until it did. */
  confirmedAt: string | null
  runId: string | null
  /** Who decided: an anonymous visitor name, or null while nobody has. */
  actor: string | null
}

export type PaymentAction = ActionBase & {
  kind: 'RECORD_PAYMENT'
  args: PaymentArgs
  preview: PaymentPreview
  result: PaymentResult | ActionFailure | null
}

export type MaintenanceAction = ActionBase & {
  kind: 'CREATE_MAINTENANCE_REQUEST'
  args: Record<string, unknown>
  preview: MaintenancePreview
  result: MaintenanceResult | ActionFailure | null
}

export type ReminderAction = ActionBase & {
  kind: 'SEND_TENANT_REMINDER'
  args: Record<string, unknown>
  preview: ReminderPreview
  result: ReminderResult | ActionFailure | null
}

/** A change the assistant proposed. Nothing is written until a person approves it. */
export type ActionView = PaymentAction | MaintenanceAction | ReminderAction

export interface ApprovalView {
  action: ActionView
  /** True when this call repeated an approval that had already been made and changed nothing. */
  replayed: boolean
}

/** The values a person may overwrite when approving a payment. */
export interface PaymentEdit {
  amount: number
  overridePayment: boolean
}

/** What approving a payment with some edits would do. Nothing was changed to produce it. */
export interface PaymentPreviewView {
  id: string
  kind: 'RECORD_PAYMENT'
  args: PaymentArgs
  preview: PaymentPreview
  summaryEn: string
  summaryAr: string
}

export type AuditEvent = 'PROPOSED' | 'EDITED' | 'APPROVED' | 'REJECTED' | 'EXPIRED' | 'FAILED' | 'RESET'

export interface AuditEntry {
  id: number
  at: string
  actor: string
  event: AuditEvent
  actionId: string | null
  entityType: string
  entityId: string
  /** The data as it was, or null when it did not exist. */
  before: unknown
  after: unknown
  note: string | null
}

export type StopReason = 'END_TURN' | 'TOOL_USE' | 'MAX_TOKENS' | 'OTHER'

/** One call to a model. A failed call has no stop reason and an error code, and spent no tokens that were reported. */
export interface RunLlmCall {
  seq: number
  model: string
  /** Uncached input tokens: the cache read and write tokens are counted apart. */
  inputTokens: number
  outputTokens: number
  cacheReadTokens: number
  cacheWriteTokens: number
  latencyMs: number
  stopReason: StopReason | null
  outcome: 'OK' | 'ERROR'
  errorCode: ErrorCode | null
  startedAt: string
}

/** One tool execution. The public trace names the tool and times it; what it was asked and said is the admin's. */
export interface RunToolCall {
  seq: number
  /** The `seq` of the model call that asked for the tool; null for a run stored before the server kept it. */
  llmSeq: number | null
  name: string
  error: boolean
  latencyMs: number
}

/** The stored trace of a run: public, and free of the question, the answer and what the tools were asked and said. */
export interface RunTrace {
  runId: string
  createdAt: string
  status: RunStatus
  provider: string
  /** The model that gave the final answer. */
  model: string
  /** Null for a run stored before routing existed. */
  routedReason: RoutedReason | null
  /** How the question scored; null when it was not scored (no larger model was configured). */
  complexity: { score: number; rules: string[] } | null
  guardrail: { status: GuardrailStatus | null; violations: number }
  totals: {
    inputTokens: number
    outputTokens: number
    cacheReadTokens: number
    cacheWriteTokens: number
    costUsd: number
    latencyMs: number
    iterations: number
    toolCalls: number
  }
  llmCalls: RunLlmCall[]
  toolCalls: RunToolCall[]
}

/** A run in the admin list. `inputTokens` are the uncached ones. */
export interface AdminRun {
  id: string
  createdAt: string
  question: string
  status: RunStatus
  provider: string
  model: string
  routedReason: RoutedReason | null
  guardrailStatus: GuardrailStatus | null
  guardrailViolations: number
  toolNames: string[]
  iterations: number
  inputTokens: number
  outputTokens: number
  cacheReadTokens: number
  cacheWriteTokens: number
  costUsd: number
  latencyMs: number
}

/** One run for the admin: its trace, the question, what each tool was asked and said, and what was not grounded. */
export interface AdminRunDetail extends RunTrace {
  question: string
  /** Joined to the trace's tool calls by `seq`. `args` is cut off, so no longer JSON, past 2000 characters. */
  toolCallDetails: { seq: number; args: string; summary: string }[]
  guardrailFindings: UnverifiedValue[]
}

export interface RunFilters {
  status?: RunStatus
  model?: string
  flaggedOnly?: boolean
}
