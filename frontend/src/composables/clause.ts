import { apiUrl } from '../api/http'
import type { LeaseSource } from '../api/types'

/** A run of the clause text, and whether it is a code or an amount that must keep its own order inside the text. */
export interface TextRun {
  text: string
  isolated: boolean
}

/** What the PDF of a document is served under; anything else the server sent is not linked to. */
const PDF_PATH = /^\/api\/lease-documents\/[A-Za-z0-9-]{1,40}\/pdf$/
/** A unit or document code (A-504, LSE-A-504) or a number (4,800.50, 3). Every repeat is bounded. */
const ISOLATED = /(?:[A-Z]{1,8}-){0,2}\d+(?:[.,]\d+){0,4}/g

/** The text direction of the language a lease document is written in. */
export function directionOf(language: LeaseSource['language']): 'ltr' | 'rtl' {
  return language === 'ar' ? 'rtl' : 'ltr'
}

/** The clause a source is from as it is cited: `7`, `7a` for the part of a clause that was split, null for the preamble. */
export function clauseOf(source: LeaseSource): string | null {
  return source.sectionNo === null ? null : source.ref.slice(source.ref.indexOf('#') + 1)
}

/** A clause's text starts with its own heading line, which the section of the viewer already shows. */
export function clauseBody(source: LeaseSource): string {
  if (source.sectionNo === null) return source.text
  const newline = source.text.indexOf('\n')
  return newline === -1 ? source.text : source.text.slice(newline + 1).trim()
}

/** The text cut into runs so that codes and amounts can be isolated from the right-to-left text around them. */
export function textRuns(text: string): TextRun[] {
  const runs: TextRun[] = []
  let from = 0
  for (const found of text.matchAll(ISOLATED)) {
    if (found.index > from) runs.push({ text: text.slice(from, found.index), isolated: false })
    runs.push({ text: found[0], isolated: true })
    from = found.index + found[0].length
  }
  if (from < text.length) runs.push({ text: text.slice(from), isolated: false })
  return runs
}

/** The PDF of the source's document, open at the page of the clause; null when the server's path is not one. */
export function pdfLink(source: LeaseSource): string | null {
  return PDF_PATH.test(source.pdfPath) ? `${apiUrl(source.pdfPath)}#page=${source.page}` : null
}
