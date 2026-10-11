// Server-sent events from a fetch response body. Kept apart from the runner so it can be tested without a backend.

/**
 * Reads a body of server-sent events and yields {event, data} for each blank-line terminated block that has both. The
 * data is parsed as JSON. A body may arrive in chunks that split an event, a line or a multi-byte character.
 *
 * @param {AsyncIterable<Uint8Array>} body
 */
export async function* readEvents(body) {
  const decoder = new TextDecoder()
  let buffer = ''
  for await (const chunk of body) {
    buffer += decoder.decode(chunk, { stream: true }).replace(/\r\n?/g, '\n')
    let boundary
    while ((boundary = buffer.indexOf('\n\n')) !== -1) {
      const block = buffer.slice(0, boundary)
      buffer = buffer.slice(boundary + 2)
      const event = /^event:\s*(.*)$/m.exec(block)?.[1]
      const data = [...block.matchAll(/^data:\s?(.*)$/gm)].map((m) => m[1]).join('\n')
      if (event && data) yield { event, data: JSON.parse(data) }
    }
  }
}
