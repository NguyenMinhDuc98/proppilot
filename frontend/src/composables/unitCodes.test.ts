import { describe, expect, it } from 'vitest'
import { splitUnitCodes } from './unitCodes'

describe('splitUnitCodes', () => {
  it('cuts the text at each unit code', () => {
    expect(splitUnitCodes('Late fee for A-203 and B-101?')).toEqual([
      { text: 'Late fee for ', code: false },
      { text: 'A-203', code: true },
      { text: ' and ', code: false },
      { text: 'B-101', code: true },
      { text: '?', code: false },
    ])
  })

  it('finds a code inside Arabic text', () => {
    expect(splitUnitCodes('عقد الوحدة A-203؟')).toEqual([
      { text: 'عقد الوحدة ', code: false },
      { text: 'A-203', code: true },
      { text: '؟', code: false },
    ])
  })

  it('leaves text without a code as one part and an empty text as none', () => {
    expect(splitUnitCodes('Which leases end soon?')).toEqual([{ text: 'Which leases end soon?', code: false }])
    expect(splitUnitCodes('')).toEqual([])
  })

  it('ignores building letters that do not exist and numbers that are too long', () => {
    expect(splitUnitCodes('Z-999 and A-2034')).toEqual([{ text: 'Z-999 and A-2034', code: false }])
  })
})
