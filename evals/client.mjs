// The backend as the eval runner talks to it: one chat question at a time, over the same HTTP API the web app uses.

import { readEvents } from './sse.mjs'
import { apply, newRun } from './stream.mjs'

const REQUEST_TIMEOUT_MS = 180_000
/** The longest part of an error body that is kept: enough to see what the server said, no more. */
const MOST_ERROR_BODY = 200

/**
 * @param {{baseUrl: string, fetch?: typeof fetch}} config
 */
export function createClient({ baseUrl, fetch: send = fetch }) {
  const root = baseUrl.replace(/\/$/, '')

  async function getJson(path) {
    const response = await send(`${root}${path}`, { signal: AbortSignal.timeout(30_000) })
    if (!response.ok) throw new Error(`HTTP ${response.status}`)
    return response.json()
  }

  return {
    /** What the backend says about itself (/api/info), or null when it does not say. */
    async info() {
      try {
        return await getJson('/api/info')
      } catch {
        return null
      }
    },

    /**
     * Asks one question and reads the whole reply.
     *
     * @param {{role: string, text: string}[]} history
     * @returns the run (see stream.mjs) and how long the call took in milliseconds
     * @throws when the request is refused (HTTP 429 for a rate limit or the daily cap, 400, ...) or the connection
     *   fails
     */
    async ask(message, history = []) {
      const started = Date.now()
      const response = await send(`${root}/api/chat`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
        body: JSON.stringify({ message, history }),
        signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
      })
      if (!response.ok) {
        throw new Error(`HTTP ${response.status}: ${(await response.text()).slice(0, MOST_ERROR_BODY)}`)
      }
      const run = newRun()
      for await (const { event, data } of readEvents(response.body)) apply(run, event, data)
      run.elapsedMs = Date.now() - started
      return run
    },

    /** The status of a proposed action as the server holds it now, or null when it cannot be read. */
    async actionStatus(id) {
      try {
        return (await getJson(`/api/actions/${encodeURIComponent(id)}`)).status ?? null
      } catch {
        return null
      }
    },
  }
}
