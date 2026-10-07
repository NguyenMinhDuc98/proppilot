import { apiUrl, failure } from './http'
import { readSse } from './sse'
import type { ChatEvent, HistoryTurn } from './types'

/** POSTs a question and yields the agent's progress as it streams in (EventSource cannot POST, so we use fetch). */
export async function* streamChat(
  message: string,
  history: HistoryTurn[],
  signal?: AbortSignal,
): AsyncGenerator<ChatEvent> {
  const response = await fetch(apiUrl('/api/chat'), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
    body: JSON.stringify({ message, history }),
    signal,
  })
  if (!response.ok) throw await failure(response)
  if (!response.body) throw new Error('Streaming is not supported by this browser')

  for await (const { event, data } of readSse(response.body)) {
    yield { type: event, ...JSON.parse(data) } as ChatEvent
  }
}
