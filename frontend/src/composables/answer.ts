import type { LeaseSource } from '../api/types'
import { renderMarkdown } from './markdown'

/** An answer is made of text, citations of lease clauses and elements. */
export type AnswerNode =
  | { kind: 'text'; text: string }
  | { kind: 'cite'; source: LeaseSource }
  | { kind: 'element'; tag: string; attrs: Record<string, string>; children: AnswerNode[] }

export interface Answer {
  nodes: AnswerNode[]
  /** The clauses the answer cites, each once, in the order it first cites them. */
  cited: LeaseSource[]
}

/**
 * A citation marker as the model writes it, such as [LSE-A-504#7] or [LSE-A-504#7a]. These limits are the server's.
 * Every repeat is bounded, so text of any length costs a fixed amount of work for each place a marker could start.
 */
const MARKER = /\[(LSE-[A-Z]{1,8}-\d{1,5}#(?:0|[1-9]\d?)[a-z]{0,4})\]/g

/** Elements nested deeper than any answer has are shown as their text, so the depth of the tree is bounded. */
const MAX_DEPTH = 24

/**
 * Renders the Markdown of an answer and turns each citation marker that names one of `sources` into a citation node.
 * A marker that names nothing the search returned stays the text it is, so a chip never points at a clause the answer
 * was not given. Links are left alone: a chip must not sit inside one.
 *
 * The tree is built from the sanitised HTML, so it holds nothing the sanitiser dropped, and it is rendered without
 * `v-html`. The text may be any text the model was led to write: one that nests deeper than the Markdown parser can
 * follow is shown as the text it is.
 */
export function buildAnswer(markdown: string, sources: readonly LeaseSource[]): Answer {
  const known = new Map(sources.map((source) => [source.ref, source]))
  const cited = new Map<string, LeaseSource>()

  function textNodes(text: string): AnswerNode[] {
    const nodes: AnswerNode[] = []
    let from = 0
    for (const marker of text.matchAll(MARKER)) {
      const source = known.get(marker[1])
      if (!source) continue
      if (marker.index > from) nodes.push({ kind: 'text', text: text.slice(from, marker.index) })
      nodes.push({ kind: 'cite', source })
      cited.set(source.ref, source)
      from = marker.index + marker[0].length
    }
    if (from < text.length) nodes.push({ kind: 'text', text: text.slice(from) })
    return nodes
  }

  /** A link keeps the text it has, so no chip sits inside it. */
  function textOf(text: string, inLink: boolean): AnswerNode[] {
    return inLink ? [{ kind: 'text', text }] : textNodes(text)
  }

  function convert(parent: ParentNode, inLink: boolean, depth: number): AnswerNode[] {
    const nodes: AnswerNode[] = []
    for (const child of parent.childNodes) {
      if (child.nodeType === Node.TEXT_NODE) {
        nodes.push(...textOf(child.textContent ?? '', inLink))
      } else if (child instanceof Element && depth >= MAX_DEPTH) {
        nodes.push(...textOf(child.textContent ?? '', inLink))
      } else if (child instanceof Element) {
        const tag = child.localName
        nodes.push({
          kind: 'element',
          tag,
          attrs: Object.fromEntries(Array.from(child.attributes, (attr) => [attr.name, attr.value])),
          children: convert(child, inLink || tag === 'a', depth + 1),
        })
      }
    }
    return nodes
  }

  const template = document.createElement('template')
  try {
    template.innerHTML = renderMarkdown(markdown)
  } catch {
    // The Markdown parser recurses once for each level of nesting and ran out of stack.
    template.content.replaceChildren(markdown)
  }
  return { nodes: convert(template.content, false, 0), cited: [...cited.values()] }
}
