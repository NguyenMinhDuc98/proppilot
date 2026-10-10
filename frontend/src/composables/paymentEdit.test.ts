import { describe, expect, it } from 'vitest'
import { parseAmount } from './paymentEdit'

describe('parseAmount', () => {
  it.each([
    ['1000', 1000],
    ['0.01', 0.01],
    ['1250.5', 1250.5],
    ['1250.50', 1250.5],
    ['10000000', 10_000_000],
    ['  300  ', 300],
  ])('reads %j as %d', (text, amount) => {
    expect(parseAmount(text)).toBe(amount)
  })

  it('reads Arabic-Indic digits and the Arabic decimal separator', () => {
    expect(parseAmount('١٢٥٠٫٧٥')).toBe(1250.75)
  })

  it.each(['', ' ', 'abc', '0', '0.00', '-5', '+5', '12.345', '1,000', '1,5', '.5', '5.', '1e3', '10000000.01', '123456789', '1 000'])(
    'refuses %j',
    (text) => {
      expect(parseAmount(text)).toBeNull()
    },
  )
})
