// Citation markers of lease clauses, as the backend writes them (CitationRefs): [LSE-A-504#7], [LSE-A-504#7a].
// Every pattern here is bounded: nothing repeats without a limit, so an answer of any length is read in time
// proportional to its size.

const DOCUMENT_CODE = 'LSE-[A-Z]{1,8}-[0-9]{1,5}'
const CLAUSE = '(?:0|[1-9][0-9]?)[a-z]{0,4}'
const MARKER = new RegExp(`\\[(${DOCUMENT_CODE}#${CLAUSE})\\]`, 'g')

/** The refs the answer cites, once each, in the order they first appear. */
export function citedRefs(answer) {
  return [...new Set([...String(answer).matchAll(MARKER)].map((found) => found[1]))]
}

/**
 * Whether the answer holds a citation marker of the lease document with this code: its code, '#', a clause number
 * and optionally the lowercase letters of a part, in square brackets. The code is matched as text, not as a pattern.
 */
export function cites(answer, documentCode) {
  const code = documentCode.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  return new RegExp(`\\[${code}#${CLAUSE}\\]`).test(answer)
}

/** The refs of the clauses a run's lease searches returned. */
export function returnedRefs(sources) {
  return [...new Set(sources.map((source) => source.ref))]
}
