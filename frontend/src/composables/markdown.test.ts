import { describe, expect, it } from 'vitest'
import { renderMarkdown } from './markdown'

describe('renderMarkdown', () => {
  it('renders bold text and lists', () => {
    const html = renderMarkdown('**Unit A-203**\n\n- first\n- second')

    expect(html).toContain('<strong>Unit A-203</strong>')
    expect(html).toContain('<li>first</li>')
  })

  it('renders GitHub-style tables', () => {
    const html = renderMarkdown('| Month | Status |\n|---|---|\n| 2026-10 | UNPAID |')

    expect(html).toContain('<div class="table-wrap"><table>')
    expect(html).toContain('</table></div>')
    expect(html).toContain('<td>UNPAID</td>')
  })

  it('keeps single line breaks, which the offline assistant relies on', () => {
    expect(renderMarkdown('line one\nline two')).toContain('<br>')
  })

  it('renders Arabic text unchanged', () => {
    expect(renderMarkdown('**أحمد الحربي**')).toContain('<strong>أحمد الحربي</strong>')
  })

  it('strips scripts, event handlers and javascript: links', () => {
    const html = renderMarkdown(
      'hi <script>alert(1)</script> <img src=x onerror="alert(2)"> [click](javascript:alert(3))',
    )

    expect(html).not.toContain('<script')
    expect(html).not.toContain('onerror')
    expect(html).not.toContain('javascript:')
  })
})
