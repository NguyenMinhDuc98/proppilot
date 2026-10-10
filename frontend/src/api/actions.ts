import { getJson, postJson } from './http'
import type { ActionView, ApprovalView, AuditEntry, PaymentEdit, PaymentPreviewView } from './types'

/**
 * A fresh random key for one proposal's approvals. `randomUUID` exists only on secure origins, so a phone that tests
 * the app over plain http on the local network gets the same strength from `getRandomValues`.
 */
export function newIdempotencyKey(): string {
  if (typeof crypto.randomUUID === 'function') return crypto.randomUUID()
  const bytes = crypto.getRandomValues(new Uint8Array(16))
  return Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('')
}

export const fetchActions = () => getJson<ActionView[]>('/api/actions')

export const fetchAction = (id: string) => getJson<ActionView>(`/api/actions/${id}`)

/** Every audit entry of one action, newest first. The unfiltered feed holds only the latest entries of all actions. */
export const fetchAudit = (actionId: string) =>
  getJson<AuditEntry[]>(`/api/audit?actionId=${encodeURIComponent(actionId)}`)

/** Recomputes the diff for an edit without deciding anything. */
export const previewAction = (id: string, edit: PaymentEdit) =>
  postJson<PaymentPreviewView>(`/api/actions/${id}/preview`, edit)

/**
 * Approves a proposal. Sending the same key again returns the first outcome instead of making the change twice, so a
 * caller keeps one key for every attempt it makes at the same action.
 */
export const approveAction = (id: string, idempotencyKey: string, edit?: PaymentEdit) =>
  postJson<ApprovalView>(`/api/actions/${id}/approve`, edit ?? {}, { 'Idempotency-Key': idempotencyKey })

export const rejectAction = (id: string, reason?: string) =>
  postJson<ActionView>(`/api/actions/${id}/reject`, reason ? { reason } : {})
