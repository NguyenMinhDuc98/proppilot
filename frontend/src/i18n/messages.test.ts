import { describe, expect, it } from 'vitest'
import ar from './ar'
import en from './en'

/** Dotted paths of every message; an array counts as one entry with its length so list sizes must match too. */
function paths(value: unknown, prefix = ''): string[] {
  if (Array.isArray(value)) return [`${prefix}[${value.length}]`]
  if (value && typeof value === 'object') {
    return Object.entries(value).flatMap(([key, child]) => paths(child, prefix ? `${prefix}.${key}` : key))
  }
  return [prefix]
}

/** Every message by its dotted path, list items included. */
function messages(value: unknown, prefix = ''): [string, string][] {
  if (typeof value === 'string') return [[prefix, value]]
  if (Array.isArray(value)) return value.flatMap((item, i) => messages(item, `${prefix}[${i}]`))
  if (value && typeof value === 'object') {
    return Object.entries(value).flatMap(([key, child]) => messages(child, prefix ? `${prefix}.${key}` : key))
  }
  return []
}

const placeholders = (message: string) => [...new Set([...message.matchAll(/\{(\w+)\}/g)].map((match) => match[1]))].sort()

describe('message catalogues', () => {
  it('define exactly the same keys in English and Arabic', () => {
    expect(paths(ar).sort()).toEqual(paths(en).sort())
  })

  it('name the same placeholders in English and Arabic for every message', () => {
    const arabic = new Map(messages(ar))
    for (const [path, message] of messages(en)) {
      expect(placeholders(arabic.get(path) ?? ''), path).toEqual(placeholders(message))
    }
  })

  it('write every lease message in both languages', () => {
    const lease = (catalogue: unknown) => messages(catalogue).filter(([path]) => path.startsWith('lease.'))
    expect(lease(en).length).toBeGreaterThan(0)
    expect(lease(ar).map(([path]) => path)).toEqual(lease(en).map(([path]) => path))
    for (const [path, message] of lease(ar)) expect(message.trim(), path).not.toBe('')
    expect(new Map(lease(ar)).get('lease.openPdf')).not.toBe(new Map(lease(en)).get('lease.openPdf'))
  })

  it.each(['chat.rechecking', 'trace.', 'guardrail.', 'admin.', 'stats.'])(
    'translate the messages of %s into Arabic instead of repeating the English',
    (prefix) => {
      const arabic = new Map(messages(ar))
      const checked = messages(en).filter(([path]) => path.startsWith(prefix))
      expect(checked.length).toBeGreaterThan(0)
      for (const [path, english] of checked) {
        const translated = arabic.get(path) ?? ''
        expect(translated.trim(), path).not.toBe('')
        expect(translated, path).not.toBe(english)
        expect(translated, path).toMatch(/[\u0600-\u06FF]/)
      }
    },
  )

  it('states the three reasons for a routed model, the verdicts of the guardrail and the filters in both languages', () => {
    const wanted = [
      'trace.routed.COMPLEX', 'trace.routed.GUARDRAIL_RETRY', 'trace.routed.FALLBACK',
      'trace.checks.OK', 'trace.checks.FLAGGED', 'trace.checks.RETRIED_OK', 'trace.checks.RETRIED_FLAGGED',
      'guardrail.flagged', 'guardrail.rechecked',
      'admin.disabled', 'admin.rejected', 'admin.loadOlder',
      'admin.filters.status', 'admin.filters.model', 'admin.filters.flaggedOnly',
    ]
    const english = new Map(messages(en))
    const arabic = new Map(messages(ar))

    for (const path of wanted) {
      expect(english.get(path), path).toBeTruthy()
      expect(arabic.get(path), path).toBeTruthy()
    }
    expect(english.get('guardrail.flagged')).toBe('Some figures could not be checked against the data')
    expect(english.get('trace.routed.COMPLEX')).toBe('Larger model: complex question')
    expect(english.get('trace.routed.GUARDRAIL_RETRY')).toBe('Re-checked after a figure could not be verified')
    expect(english.get('trace.routed.FALLBACK')).toBe('Fallback model: the first one was busy')
  })
})
