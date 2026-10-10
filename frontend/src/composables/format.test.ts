import { describe, expect, it } from 'vitest'
import { formatCountdown, formatDate, formatDateTime, formatMoney, formatMonth } from './format'

describe('formatMoney', () => {
  it('always shows two decimals with Latin digits', () => {
    expect(formatMoney(3500)).toBe('3,500.00')
    expect(formatMoney(0.5)).toBe('0.50')
    expect(formatMoney(14399.995)).toBe('14,400.00')
  })
})

describe('dates', () => {
  it('names the month of an invoice without letting a time zone move it', () => {
    expect(formatMonth('2026-03-01', 'en')).toBe('Mar 2026')
    expect(formatMonth('2026-03-01', 'ar')).toMatch(/2026/)
    expect(formatMonth('2026-03-01', 'ar')).not.toMatch(/[٠-٩]/)
  })

  it('writes a payment date and a point in time with Latin digits in Arabic too', () => {
    expect(formatDate('2026-10-10', 'en')).toBe('Oct 10, 2026')
    expect(formatDate('2026-10-10', 'ar')).not.toMatch(/[٠-٩]/)
    expect(formatDateTime('2026-10-10T15:00:00Z', 'ar')).not.toMatch(/[٠-٩]/)
  })
})

describe('formatCountdown', () => {
  it('shows minutes and padded seconds and rounds a part of a second up', () => {
    expect(formatCountdown(15 * 60_000)).toBe('15:00')
    expect(formatCountdown(61_000)).toBe('1:01')
    expect(formatCountdown(59_001)).toBe('1:00')
    expect(formatCountdown(0)).toBe('0:00')
  })
})
