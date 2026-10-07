import { describe, expect, it } from 'vitest'
import { SseParser, readSse } from './sse'

describe('SseParser', () => {
  it('parses complete events', () => {
    const events = new SseParser().push('event:token\ndata:{"text":"Hi"}\n\nevent:done\ndata:{}\n\n')

    expect(events).toEqual([
      { event: 'token', data: '{"text":"Hi"}' },
      { event: 'done', data: '{}' },
    ])
  })

  it('buffers an event split across chunks', () => {
    const parser = new SseParser()

    expect(parser.push('event:tok')).toEqual([])
    expect(parser.push('en\ndata:{"te')).toEqual([])
    expect(parser.push('xt":"Hi"}\n')).toEqual([])
    expect(parser.push('\n')).toEqual([{ event: 'token', data: '{"text":"Hi"}' }])
  })

  it('handles CRLF line endings', () => {
    expect(new SseParser().push('event: token\r\ndata: x\r\n\r\n')).toEqual([{ event: 'token', data: 'x' }])
  })

  it('joins multi-line data and ignores comments', () => {
    expect(new SseParser().push(': keep-alive\nevent:token\ndata:line1\ndata:line2\n\n')).toEqual([
      { event: 'token', data: 'line1\nline2' },
    ])
  })

  it('defaults the event name to "message"', () => {
    expect(new SseParser().push('data:x\n\n')).toEqual([{ event: 'message', data: 'x' }])
  })

  it('skips blocks without data', () => {
    expect(new SseParser().push('event:ping\n\n')).toEqual([])
  })
})

describe('readSse', () => {
  const stream = (chunks: Uint8Array[]) =>
    new ReadableStream<Uint8Array>({
      start(controller) {
        chunks.forEach((c) => controller.enqueue(c))
        controller.close()
      },
    })

  async function collect(body: ReadableStream<Uint8Array>) {
    const out = []
    for await (const e of readSse(body)) out.push(e)
    return out
  }

  it('decodes multi-byte characters split across network chunks', async () => {
    const bytes = new TextEncoder().encode('event:token\ndata:{"text":"أحمد"}\n\n')
    const cut = bytes.indexOf(0xd8) + 1 // splits the first Arabic letter in half

    const events = await collect(stream([bytes.slice(0, cut), bytes.slice(cut)]))

    expect(events).toEqual([{ event: 'token', data: '{"text":"أحمد"}' }])
  })

  it('flushes a final event that lacks the trailing blank line', async () => {
    const bytes = new TextEncoder().encode('event:done\ndata:{"ok":true}\n')

    expect(await collect(stream([bytes]))).toEqual([{ event: 'done', data: '{"ok":true}' }])
  })
})
