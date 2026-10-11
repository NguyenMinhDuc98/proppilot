import { describe, expect, it } from 'vitest'

const sources = import.meta.glob<string>(['./*.vue', '../views/*.vue'], { query: '?raw', import: 'default', eager: true })

/** Physical directions do not flip with the page; these components must use start and end, so Arabic mirrors. */
const PHYSICAL = /(?:^|[\s;{])(?:margin|padding|border)-(?:left|right)\b|(?:^|[\s;{])(?:left|right)\s*:|text-align\s*:\s*(?:left|right)|float\s*:\s*(?:left|right)/m

const files = [
  './GuardrailBadge.vue',
  './GuardrailValues.vue',
  './TraceTimeline.vue',
  './TraceDrawer.vue',
  './AdminRunRow.vue',
  './AdminRunDrawer.vue',
  '../views/AdminTracesView.vue',
  '../views/StatsView.vue',
]

describe('right-to-left support', () => {
  for (const file of files) {
    it(`${file} styles with logical properties only`, () => {
      const source = sources[file]
      expect(source, file).toBeTypeOf('string')

      expect(source.slice(source.indexOf('<style'))).not.toMatch(PHYSICAL)
    })
  }

  it('would notice a physical property', () => {
    expect('.a { margin-left: 4px; }').toMatch(PHYSICAL)
    expect('.a { text-align: right; }').toMatch(PHYSICAL)
    expect('.a { inset-inline-start: 0; margin-inline-end: 4px; }').not.toMatch(PHYSICAL)
  })
})
