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

describe('message catalogues', () => {
  it('define exactly the same keys in English and Arabic', () => {
    expect(paths(ar).sort()).toEqual(paths(en).sort())
  })
})
