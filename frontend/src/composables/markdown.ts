import DOMPurify from 'dompurify'
import { marked } from 'marked'

marked.setOptions({ gfm: true, breaks: true })

/**
 * Renders model output (Markdown) to safe HTML. The text can include tenant-supplied strings that reached the model
 * through tool results, so it is never trusted: everything goes through DOMPurify before reaching v-html.
 */
export function renderMarkdown(text: string): string {
  const html = marked.parse(text, { async: false })
  const safe = DOMPurify.sanitize(html, { USE_PROFILES: { html: true } })
  // Tables get a scrollable, rounded wrapper so they read as a data card and never overflow a phone screen.
  return safe.replaceAll('<table>', '<div class="table-wrap"><table>').replaceAll('</table>', '</table></div>')
}
