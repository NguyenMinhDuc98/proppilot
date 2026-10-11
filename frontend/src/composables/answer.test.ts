import { describe, expect, it } from 'vitest'
import { arabicLateFee, lateFee, noticePart, preamble } from '../test/leaseFixtures'
import { buildAnswer, type AnswerNode } from './answer'

const sources = [lateFee, noticePart, preamble, arabicLateFee]

/** The text of a tree with each citation written as `{ref}`, so one string shows where the chips would be. */
function written(nodes: AnswerNode[]): string {
  return nodes
    .map((node) => {
      if (node.kind === 'text') return node.text
      if (node.kind === 'cite') return `{${node.source.ref}}`
      return written(node.children)
    })
    .join('')
    .trim()
}

/** Every element of the tree, depth first. */
function elements(nodes: AnswerNode[]): Extract<AnswerNode, { kind: 'element' }>[] {
  return nodes.flatMap((node) => (node.kind === 'element' ? [node, ...elements(node.children)] : []))
}

const citations = (nodes: AnswerNode[]): string[] =>
  nodes.flatMap((node) => {
    if (node.kind === 'cite') return [node.source.ref]
    return node.kind === 'element' ? citations(node.children) : []
  })

describe('buildAnswer', () => {
  it('turns a marker that names a returned clause into a citation inside its sentence', () => {
    const { nodes, cited } = buildAnswer('The late fee is 3% [LSE-A-504#5] of the rent.', sources)

    expect(nodes[0]).toEqual({
      kind: 'element',
      tag: 'p',
      attrs: {},
      children: [
        { kind: 'text', text: 'The late fee is 3% ' },
        { kind: 'cite', source: lateFee },
        { kind: 'text', text: ' of the rent.' },
      ],
    })
    expect(cited).toEqual([lateFee])
  })

  it('leaves a marker that names no returned clause as the text it is', () => {
    const { nodes, cited } = buildAnswer('The deposit is two months [LSE-A-504#9] and the fee [LSE-B-101#5].', sources)

    expect(citations(nodes)).toEqual([])
    expect(written(nodes)).toBe('The deposit is two months [LSE-A-504#9] and the fee [LSE-B-101#5].')
    expect(cited).toEqual([])
  })

  it('turns only the markers that resolve when an answer has both', () => {
    const { nodes, cited } = buildAnswer('Fee [LSE-A-504#5], notice [LSE-A-504#8].', sources)

    expect(written(nodes)).toBe('Fee {LSE-A-504#5}, notice [LSE-A-504#8].')
    expect(cited).toEqual([lateFee])
  })

  it('makes no citation without any source, such as a clause cited from an earlier question', () => {
    const { nodes, cited } = buildAnswer('The fee is 3% [LSE-A-504#5].', [])

    expect(written(nodes)).toBe('The fee is 3% [LSE-A-504#5].')
    expect(cited).toEqual([])
  })

  it('reads the letter of a part, and the preamble', () => {
    const { nodes } = buildAnswer('Notice [LSE-A-504#4a]. Parties [LSE-A-504#0].', sources)

    expect(citations(nodes)).toEqual(['LSE-A-504#4a', 'LSE-A-504#0'])
  })

  it('does not take the whole clause for a part of it, or a part for another', () => {
    const { nodes } = buildAnswer('[LSE-A-504#4] [LSE-A-504#4b] [LSE-A-504#4a]', sources)

    expect(written(nodes)).toBe('[LSE-A-504#4] [LSE-A-504#4b] {LSE-A-504#4a}')
  })

  it('turns markers that stand next to each other, with and without a space between them', () => {
    const { nodes, cited } = buildAnswer('Both apply [LSE-A-504#5][LSE-A-504#4a] [LSE-A-504#0]', sources)

    expect(written(nodes)).toBe('Both apply {LSE-A-504#5}{LSE-A-504#4a} {LSE-A-504#0}')
    expect(cited.map((source) => source.ref)).toEqual(['LSE-A-504#5', 'LSE-A-504#4a', 'LSE-A-504#0'])
  })

  it('lists each cited clause once, in the order it is first cited', () => {
    const { cited } = buildAnswer('A [LSE-A-504#4a] B [LSE-A-504#5] C [LSE-A-504#4a] D [LSE-A-504#5]', sources)

    expect(cited.map((source) => source.ref)).toEqual(['LSE-A-504#4a', 'LSE-A-504#5'])
  })

  it('finds markers in list items', () => {
    const { nodes } = buildAnswer('- Fee: 3% [LSE-A-504#5]\n- Notice: 60 days [LSE-A-504#4a]', sources)

    const items = elements(nodes).filter((element) => element.tag === 'li')
    expect(items.map((item) => written(item.children))).toEqual(['Fee: 3% {LSE-A-504#5}', 'Notice: 60 days {LSE-A-504#4a}'])
  })

  it('finds markers in the cells of a table', () => {
    const { nodes } = buildAnswer(
      '| Unit | Late fee |\n|---|---|\n| A-504 | 3% [LSE-A-504#5] |\n| A-203 | 3% [LSE-A-203#5] |',
      sources,
    )

    const cells = elements(nodes).filter((element) => element.tag === 'td')
    expect(cells.map((cell) => written(cell.children))).toEqual([
      'A-504', '3% {LSE-A-504#5}', 'A-203', '3% {LSE-A-203#5}',
    ])
  })

  it('finds markers inside bold text and a quotation', () => {
    const { nodes } = buildAnswer('**Fee 3% [LSE-A-504#5]**\n\n> as quoted [LSE-A-504#0]', sources)

    expect(citations(nodes)).toEqual(['LSE-A-504#5', 'LSE-A-504#0'])
  })

  it('finds a marker in Arabic text, before and after the words', () => {
    const { nodes, cited } = buildAnswer('[LSE-A-203#5] غرامة التأخير 3 بالمائة [LSE-A-203#5] من الإيجار', sources)

    expect(written(nodes)).toBe('{LSE-A-203#5} غرامة التأخير 3 بالمائة {LSE-A-203#5} من الإيجار')
    expect(cited).toEqual([arabicLateFee])
  })

  it('keeps the markup of the answer around a citation', () => {
    const { nodes } = buildAnswer('**Unit A-504**: fee [LSE-A-504#5]\n\nnext', sources)

    expect(elements(nodes).map((element) => element.tag)).toEqual(['p', 'strong', 'p'])
  })

  it('leaves a marker that is the text of a link as the text of the link', () => {
    const { nodes, cited } = buildAnswer('[[LSE-A-504#5]](https://example.com/page) and [LSE-A-504#5]', sources)

    const link = elements(nodes).find((element) => element.tag === 'a')
    expect(link?.attrs.href).toBe('https://example.com/page')
    expect(written(link?.children ?? [])).toBe('[LSE-A-504#5]')
    expect(citations(link?.children ?? [])).toEqual([])
    expect(cited).toEqual([lateFee])
  })

  describe('with text that imitates a marker or carries markup', () => {
    it('makes no citation of a marker whose ref carries an attribute break-out', () => {
      const attack = '[LSE-A-504#5"><img src=x onerror=alert(1)>]'
      const { nodes } = buildAnswer(`Fee ${attack}`, sources)

      expect(citations(nodes)).toEqual([])
      expect(elements(nodes).map((element) => element.tag)).not.toContain('script')
      expect(JSON.stringify(nodes)).not.toContain('onerror')
    })

    it('makes no citation of a ref that only starts like a returned one', () => {
      const { nodes } = buildAnswer('[LSE-A-504#5 ] [ LSE-A-504#5] [lse-a-504#5] [LSE-A-504 #5] [LSE-A-504#05] [LSE-A-504#5ab1]', sources)

      expect(citations(nodes)).toEqual([])
    })

    it('strips scripts and handlers from markup written around a marker that resolves', () => {
      const { nodes, cited } = buildAnswer(
        '<script>alert(1)</script><img src=x onerror="alert(2)"> fee [LSE-A-504#5] <a href="javascript:alert(3)">x</a>',
        sources,
      )

      expect(citations(nodes)).toEqual(['LSE-A-504#5'])
      expect(cited).toEqual([lateFee])
      const tags = elements(nodes)
      expect(tags.map((element) => element.tag)).not.toContain('script')
      expect(JSON.stringify(nodes)).not.toMatch(/onerror|javascript:/)
    })

    it('does not read an escaped marker or one in an HTML comment as a citation of another clause', () => {
      const { nodes } = buildAnswer('<!-- [LSE-A-504#5] -->\\[LSE-A-504#4a\\]', sources)

      expect(citations(nodes)).toEqual([])
    })

    it('shows the text of an element name or attribute that a marker was written inside as text', () => {
      const { nodes } = buildAnswer('<span title="[LSE-A-504#5]">fee</span>', sources)

      const span = elements(nodes).find((element) => element.tag === 'span')
      expect(span?.attrs.title).toBe('[LSE-A-504#5]')
      expect(citations(nodes)).toEqual([])
    })
  })

  it('shows what is nested deeper than an answer ever is as the text of its elements', () => {
    const { nodes, cited } = buildAnswer(`${'> '.repeat(100)}deep [LSE-A-504#5]`, sources)

    let depth = 0
    for (let level: AnswerNode[] = nodes; level.length; depth++) {
      level = level.flatMap((node) => (node.kind === 'element' ? node.children : []))
    }
    expect(depth).toBeLessThanOrEqual(26)
    expect(written(nodes)).toBe('deep {LSE-A-504#5}')
    expect(cited).toEqual([lateFee])
  })

  it('shows text that nests deeper than the Markdown parser can follow as the text it is', () => {
    const { nodes, cited } = buildAnswer(`${'> '.repeat(20_000)}too deep [LSE-A-504#5]`, sources)

    expect(written(nodes)).toContain('too deep {LSE-A-504#5}')
    expect(cited).toEqual([lateFee])
  })

  it('reads text of any length in the time it takes to read it', () => {
    const hostile = '[LSE-AAAAAAAA-99999#99aaaaa '.repeat(20_000) + '[LSE-A-504#5]'
    const { cited } = buildAnswer(hostile, sources)

    expect(cited).toEqual([lateFee])
  })
})
