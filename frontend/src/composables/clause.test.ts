import { describe, expect, it } from 'vitest'
import { arabicLateFee, lateFee, noticePart, preamble } from '../test/leaseFixtures'
import { clauseBody, clauseOf, directionOf, pdfLink, textRuns } from './clause'

describe('clauseOf', () => {
  it('reads the number of a clause, the letter of a part and the preamble from the source', () => {
    expect(clauseOf(lateFee)).toBe('5')
    expect(clauseOf(noticePart)).toBe('4a')
    expect(clauseOf(preamble)).toBeNull()
  })
})

describe('directionOf', () => {
  it('writes Arabic right to left and English left to right', () => {
    expect(directionOf('ar')).toBe('rtl')
    expect(directionOf('en')).toBe('ltr')
  })
})

describe('clauseBody', () => {
  it('leaves out the heading line that every clause text starts with', () => {
    expect(clauseBody(lateFee)).toBe(
      'Rent not received within 5 days of its due date carries a late fee of 3% of the monthly rent, SAR 150.00 for each month.',
    )
    expect(clauseBody(arabicLateFee)).toBe(
      'إذا لم يصل الإيجار خلال 5 أيام من تاريخ استحقاقه تستحق غرامة قدرها 3 بالمائة، أي 144 ريال سعودي.',
    )
  })

  it('keeps the whole text of the preamble, which has no heading', () => {
    expect(clauseBody(preamble)).toBe(preamble.text)
  })

  it('keeps a text that is only a heading', () => {
    expect(clauseBody({ ...lateFee, text: '5. Late payment' })).toBe('5. Late payment')
  })
})

describe('textRuns', () => {
  it('isolates unit codes and amounts from the text around them', () => {
    expect(textRuns('Unit A-504 pays SAR 4,800.50 for 12 months, LSE-A-504.')).toEqual([
      { text: 'Unit ', isolated: false },
      { text: 'A-504', isolated: true },
      { text: ' pays SAR ', isolated: false },
      { text: '4,800.50', isolated: true },
      { text: ' for ', isolated: false },
      { text: '12', isolated: true },
      { text: ' months, ', isolated: false },
      { text: 'LSE-A-504', isolated: true },
      { text: '.', isolated: false },
    ])
  })

  it('leaves text without codes or numbers as one run, and finds them in Arabic text', () => {
    expect(textRuns('no digits here')).toEqual([{ text: 'no digits here', isolated: false }])
    expect(textRuns('غرامة 3 بالمائة للوحدة A-203')).toEqual([
      { text: 'غرامة ', isolated: false },
      { text: '3', isolated: true },
      { text: ' بالمائة للوحدة ', isolated: false },
      { text: 'A-203', isolated: true },
    ])
    expect(textRuns('')).toEqual([])
  })

  it('keeps every character of the text', () => {
    const text = 'a 1,2.3,4.5.6 A-1 B-C-D-5 x'
    expect(textRuns(text).map((run) => run.text).join('')).toBe(text)
  })

  it('reads hostile text of any length in the time it takes to read it', () => {
    for (const unit of ['A-', '1,', '1.1,', 'AAAAAAAAA-', '9']) {
      const runs = textRuns(unit.repeat(100_000))
      expect(runs.map((run) => run.text).join('')).toBe(unit.repeat(100_000))
    }
  })
})

describe('pdfLink', () => {
  it('opens the document at the page of the clause', () => {
    expect(pdfLink(lateFee)).toBe('/api/lease-documents/LSE-A-504/pdf#page=2')
    expect(pdfLink(arabicLateFee)).toBe('/api/lease-documents/LSE-A-203/pdf#page=3')
  })

  it.each([
    'javascript:alert(1)',
    '//example.com/api/lease-documents/LSE-A-504/pdf',
    'https://example.com/api/lease-documents/LSE-A-504/pdf',
    '/api/lease-documents/LSE-A-504/pdf?x=1',
    '/api/lease-documents/../../admin/pdf',
    '',
  ])('does not link to %j', (pdfPath) => {
    expect(pdfLink({ ...lateFee, pdfPath })).toBeNull()
  })
})
