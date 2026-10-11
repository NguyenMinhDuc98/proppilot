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
