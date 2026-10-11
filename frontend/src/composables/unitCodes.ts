export interface TextPart {
  text: string
  code: boolean
}

const UNIT_CODE = /\b[A-F]-\d{3}\b/g

/** The text cut at its unit codes, so a code can be isolated from the writing direction and kept on one line. */
export function splitUnitCodes(text: string): TextPart[] {
  const parts: TextPart[] = []
  let from = 0
  for (const match of text.matchAll(UNIT_CODE)) {
    if (match.index > from) {
      parts.push({ text: text.slice(from, match.index), code: false })
    }
    parts.push({ text: match[0], code: true })
    from = match.index + match[0].length
  }
  if (from < text.length) {
    parts.push({ text: text.slice(from), code: false })
  }
  return parts
}
