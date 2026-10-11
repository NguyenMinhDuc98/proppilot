import DOMPurify from 'dompurify'
import { marked } from 'marked'

marked.setOptions({ gfm: true, breaks: true })

/**
 * Renders model output (Markdown) to a tree of safe nodes. The text can include tenant-supplied strings that reached
 * the model through tool results, so it is never trusted: everything goes through DOMPurify. The sanitised tree is
 * returned as it is rather than as a string, because editing a string of HTML (or parsing it a second time) is how
 * markup that sat inside an attribute value comes back as elements.
 */
export function renderMarkdown(text: string): DocumentFragment {
  const html = marked.parse(text, { async: false })
  return DOMPurify.sanitize(html, { USE_PROFILES: { html: true }, RETURN_DOM_FRAGMENT: true })
}
