import DOMPurify from 'dompurify'
import { marked } from 'marked'

marked.setOptions({ gfm: true, breaks: true })

/**
 * Nothing in an answer may make the browser fetch something by itself: an image whose address holds what the model
 * was told to put there would send it to another host the moment the answer is drawn. A link is only followed when a
 * person clicks it, so links stay, and open in a new tab without handing over the page.
 */
const NEVER_RENDERED = ['img', 'picture', 'source', 'video', 'audio', 'track', 'iframe', 'frame', 'object', 'embed',
  'form', 'input', 'button', 'textarea', 'select', 'link', 'meta', 'base', 'style']

DOMPurify.addHook('afterSanitizeAttributes', (node) => {
  if (node.tagName === 'A') {
    node.setAttribute('target', '_blank')
    node.setAttribute('rel', 'noopener noreferrer')
  }
})

/**
 * Renders model output (Markdown) to a tree of safe nodes. The text can include tenant-supplied strings that reached
 * the model through tool results, so it is never trusted: everything goes through DOMPurify. The sanitised tree is
 * returned as it is rather than as a string, because editing a string of HTML (or parsing it a second time) is how
 * markup that sat inside an attribute value comes back as elements.
 */
export function renderMarkdown(text: string): DocumentFragment {
  const html = marked.parse(text, { async: false })
  const tree = DOMPurify.sanitize(html, {
    USE_PROFILES: { html: true },
    FORBID_TAGS: NEVER_RENDERED,
    RETURN_DOM_FRAGMENT: true,
  })
  // A quoted clause is in the language of its lease, which may not be the language of the answer around it.
  tree.querySelectorAll('blockquote').forEach((quote) => quote.setAttribute('dir', 'auto'))
  return tree
}
