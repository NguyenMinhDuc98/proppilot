export interface SseEvent {
  event: string
  data: string
}

/**
 * Incremental parser for a server-sent-event stream. Feed it decoded text chunks as they arrive; it returns the
 * events completed by that chunk and keeps any partial event buffered for the next call.
 */
export class SseParser {
  private buffer = ''
  /** A trailing CR is held back: the next chunk may start with the LF of a CRLF pair. */
  private heldCr = ''

  push(chunk: string): SseEvent[] {
    let text = this.heldCr + chunk
    this.heldCr = text.endsWith('\r') ? '\r' : ''
    if (this.heldCr) text = text.slice(0, -1)
    this.buffer += text.replace(/\r\n?/g, '\n')

    const blocks = this.buffer.split('\n\n')
    this.buffer = blocks.pop() ?? ''
    return blocks.flatMap((block) => parseBlock(block) ?? [])
  }
}

function parseBlock(block: string): SseEvent | null {
  let event = 'message'
  const data: string[] = []
  for (const line of block.split('\n')) {
    if (line === '' || line.startsWith(':')) continue
    const colon = line.indexOf(':')
    const field = colon === -1 ? line : line.slice(0, colon)
    const value = colon === -1 ? '' : line.slice(colon + 1).replace(/^ /, '')
    if (field === 'event') event = value
    else if (field === 'data') data.push(value)
  }
  return data.length ? { event, data: data.join('\n') } : null
}

/** Reads a fetch response body as UTF-8 and yields SSE events; multi-byte characters split across chunks are safe. */
export async function* readSse(body: ReadableStream<Uint8Array>): AsyncGenerator<SseEvent> {
  const reader = body.getReader()
  const decoder = new TextDecoder('utf-8')
  const parser = new SseParser()
  try {
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      yield* parser.push(decoder.decode(value, { stream: true }))
    }
    yield* parser.push(decoder.decode() + '\n\n')
  } finally {
    reader.releaseLock()
  }
}
