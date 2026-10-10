import type { PaymentEdit, PaymentPreviewView } from '../api/types'

/** The most one payment may record; the server refuses more. */
const MAX_AMOUNT = 10_000_000

const ARABIC_INDIC_DIGITS = '٠١٢٣٤٥٦٧٨٩'

/**
 * Reads an amount a person typed: above 0 and at most 10,000,000, with at most 2 decimals. Arabic-Indic digits and
 * the Arabic decimal separator are accepted because Arabic keyboards type them. Anything else, including thousands
 * separators that could be a decimal comma, is null rather than guessed at.
 */
export function parseAmount(raw: string): number | null {
  const text = raw
    .trim()
    .replace(/[٠-٩]/g, (digit) => String(ARABIC_INDIC_DIGITS.indexOf(digit)))
    .replace('٫', '.')
  if (!/^\d{1,8}(\.\d{1,2})?$/.test(text)) return null
  const amount = Number(text)
  return amount > 0 && amount <= MAX_AMOUNT ? amount : null
}

/** What the amount editor knows about the payment on display, for the card around it. */
export interface PaymentDraft {
  /** What approving will carry; null while nothing differs from the proposal. */
  edit: PaymentEdit | null
  /** The diff for that edit; null while the amount is above the balance and overpayment is not allowed. */
  preview: PaymentPreviewView | null
  /** True when the diff on display is exactly what Approve would do. */
  ready: boolean
}
