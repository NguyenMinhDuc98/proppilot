import { createI18n } from 'vue-i18n'
import { describe, expect, it } from 'vitest'
import ar from './ar'
import en from './en'

function usageLabel(locale: 'en' | 'ar', count: number): string {
  const i18n = createI18n({ legacy: false, locale, messages: { en, ar } })
  return i18n.global.t('chat.usage.toolCalls', { n: count }, count)
}

describe('tool call count wording', () => {
  it('uses the singular for exactly one call in English', () => {
    expect(usageLabel('en', 1)).toBe('1 tool call')
  })

  it('uses the plural for several calls and says none for zero in English', () => {
    expect(usageLabel('en', 2)).toBe('2 tool calls')
    expect(usageLabel('en', 0)).toBe('no tool calls')
  })

  it('has the matching singular, plural and none forms in Arabic', () => {
    expect(usageLabel('ar', 1)).toBe('استدعاء أداة واحد')
    expect(usageLabel('ar', 3)).toBe('3 استدعاءات أدوات')
    expect(usageLabel('ar', 0)).toBe('بدون استدعاء أدوات')
  })
})
