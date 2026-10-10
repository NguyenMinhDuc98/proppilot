import type {
  AuditEntry,
  MaintenanceAction,
  PaymentAction,
  PaymentPreview,
  PaymentPreviewView,
  ReminderAction,
} from '../api/types'

const base = {
  status: 'PROPOSED' as const,
  createdAt: '2026-10-10T15:00:00Z',
  // Far away, so a test sees an open proposal however late it runs; tests of the countdown set their own.
  expiresAt: '2099-01-01T00:00:00Z',
  decidedAt: null,
  decisionReason: null,
  confirmedAt: null,
  runId: 'run-1',
  actor: null,
}

export const paymentPreview: PaymentPreview = {
  unitCode: 'B-203',
  tenantEn: 'Ahmed Al-Harbi',
  tenantAr: 'أحمد الحربي',
  amount: 5000,
  paymentDate: '2026-10-10',
  method: 'BANK_TRANSFER',
  outstandingBefore: 14400,
  outstandingAfter: 9400,
  overpaymentCredit: 0,
  payments: [
    { paymentId: 11, period: '2026-07-01', amount: 4800, paidBefore: 0, applied: 4800, paidAfter: 4800, remainingAfter: 0, status: 'PAID' },
    { paymentId: 12, period: '2026-08-01', amount: 4800, paidBefore: 0, applied: 200, paidAfter: 200, remainingAfter: 4600, status: 'PARTIAL' },
  ],
}

export function paymentAction(overrides: Partial<PaymentAction> = {}): PaymentAction {
  return {
    ...base,
    id: '11111111-1111-4111-8111-111111111111',
    kind: 'RECORD_PAYMENT',
    args: { unit_code: 'B-203', amount: 5000, payment_date: '2026-10-10', method: 'BANK_TRANSFER', overridePayment: false },
    preview: paymentPreview,
    summaryEn: 'Record a SAR 5,000.00 bank transfer payment from Ahmed Al-Harbi for unit B-203.',
    summaryAr: 'تسجيل دفعة بقيمة 5,000.00 ريال سعودي من أحمد الحربي للوحدة B-203.',
    result: null,
    ...overrides,
  }
}

/** What the preview endpoint answers for a payment of `amount` against the same invoices. */
export function paymentPreviewView(amount: number, overrides: Partial<PaymentPreview> = {}): PaymentPreviewView {
  return {
    id: '11111111-1111-4111-8111-111111111111',
    kind: 'RECORD_PAYMENT',
    args: { unit_code: 'B-203', amount, payment_date: '2026-10-10', method: 'BANK_TRANSFER', overridePayment: false },
    preview: {
      ...paymentPreview,
      amount,
      outstandingAfter: paymentPreview.outstandingBefore - amount,
      payments: [
        { paymentId: 11, period: '2026-07-01', amount: 4800, paidBefore: 0, applied: 1000, paidAfter: 1000, remainingAfter: 3800, status: 'PARTIAL' },
      ],
      ...overrides,
    },
    summaryEn: `Record a SAR ${amount.toFixed(2)} bank transfer payment from Ahmed Al-Harbi for unit B-203.`,
    summaryAr: `تسجيل دفعة بقيمة ${amount.toFixed(2)} ريال سعودي من أحمد الحربي للوحدة B-203.`,
  }
}

export function maintenanceAction(overrides: Partial<MaintenanceAction> = {}): MaintenanceAction {
  return {
    ...base,
    id: '22222222-2222-4222-8222-222222222222',
    kind: 'CREATE_MAINTENANCE_REQUEST',
    args: { unit_code: 'A-301', category: 'PLUMBING', priority: 'HIGH', description: 'Kitchen sink leaks' },
    preview: {
      unitCode: 'A-301',
      building: 'Al Olaya Tower',
      category: 'PLUMBING',
      priority: 'HIGH',
      description: 'Kitchen sink leaks',
      status: 'OPEN',
    },
    summaryEn: 'Create a maintenance request for unit A-301 (plumbing, high priority): "Kitchen sink leaks".',
    summaryAr: 'إنشاء طلب صيانة للوحدة A-301 (سباكة، أولوية عالية): «Kitchen sink leaks».',
    result: null,
    ...overrides,
  }
}

export function reminderAction(overrides: Partial<ReminderAction> = {}): ReminderAction {
  return {
    ...base,
    id: '33333333-3333-4333-8333-333333333333',
    kind: 'SEND_TENANT_REMINDER',
    args: { unit_code: 'B-203', purpose: 'RENT_REMINDER', language: 'AR' },
    preview: {
      unitCode: 'B-203',
      tenantEn: 'Ahmed Al-Harbi',
      tenantAr: 'أحمد الحربي',
      purpose: 'RENT_REMINDER',
      language: 'AR',
      subject: 'تذكير بسداد الإيجار',
      body: 'عزيزي أحمد، نذكّركم بسداد الإيجار المتأخر.',
      delivered: false,
    },
    summaryEn: 'Queue a rent reminder in Arabic for Ahmed Al-Harbi (unit B-203). It is stored in the outbox; nothing is sent.',
    summaryAr: 'إضافة رسالة تذكير بالإيجار باللغة العربية إلى أحمد الحربي (الوحدة B-203).',
    result: null,
    ...overrides,
  }
}

export function auditEntry(overrides: Partial<AuditEntry> = {}): AuditEntry {
  return {
    id: 1,
    at: '2026-10-10T15:00:00Z',
    actor: 'agent',
    event: 'PROPOSED',
    actionId: '11111111-1111-4111-8111-111111111111',
    entityType: 'PENDING_ACTION',
    entityId: '11111111-1111-4111-8111-111111111111',
    before: null,
    after: { kind: 'RECORD_PAYMENT' },
    note: null,
    ...overrides,
  }
}
