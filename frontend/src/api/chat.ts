import { apiUrl, failure } from './http'
import { readSse } from './sse'
import type { ChatEvent, HistoryTurn } from './types'

/** POSTs a question and yields the agent's progress as it streams in (EventSource cannot POST, so we use fetch). */
export const streamChat = (message: string, history: HistoryTurn[], signal?: AbortSignal) =>
  stream({ message, history }, signal)

/**
 * Asks the assistant to confirm a decision a person took on a proposal. The server writes that turn from the stored
 * action, so the request carries only the action's id and the earlier conversation.
 */
export const streamConfirmation = (actionId: string, history: HistoryTurn[], signal?: AbortSignal) =>
  stream({ actionId, history }, signal)

async function* stream(body: object, signal?: AbortSignal): AsyncGenerator<ChatEvent> {
  const response = await fetch(apiUrl('/api/chat'), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
    body: JSON.stringify(body),
    signal,
  })
  if (!response.ok) throw await failure(response)
  if (!response.body) throw new Error('Streaming is not supported by this browser')

  for await (const { event, data } of readSse(response.body)) {
    yield { type: event, ...JSON.parse(data) } as ChatEvent
  }
}
