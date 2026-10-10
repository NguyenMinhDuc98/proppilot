export interface DonePayload {
  runId: string
  status: 'OK' | 'ERROR' | 'MAX_ITERATIONS' | 'TRUNCATED'
  provider: string
  model: string
  inputTokens: number
  outputTokens: number
  toolCalls: number
  iterations: number
  latencyMs: number
  costUsd: number
  /** The answer hit the model's length limit and was cut off. */
  truncated: boolean
}

/** Why a run failed; the UI shows its own localised text for each, never the server's message. */
export type ErrorCode = 'llm_overloaded' | 'llm_timeout' | 'llm_auth' | 'llm_error' | 'run_timeout'

export type ChatEvent =
  | { type: 'tool_call'; name: string; args: Record<string, unknown> }
  | { type: 'tool_result'; name: string; summary: string; error: boolean }
  | ({ type: 'action_proposed' } & ActionView)
  | { type: 'token'; text: string }
  | { type: 'error'; message: string; code: ErrorCode }
  | ({ type: 'done' } & DonePayload)

export interface HistoryTurn {
  role: 'user' | 'assistant'
  text: string
}

/** Which assistant the backend runs (real Claude, or the free built-in offline one) and the history limit it enforces. */
export interface AppInfo {
  mode: 'claude' | 'offline'
  provider: string
  model: string
  maxHistoryItems: number
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
