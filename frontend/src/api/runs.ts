import { getJson } from './http'
import type { RunTrace } from './types'

/** A trace is written once, when its run ends, so one that was read is kept for as long as the page lives. */
const read = new Map<string, Promise<RunTrace>>()

/** The stored trace of a run, by the id its `done` event carried. 404 for a run the server does not know. */
export function fetchRunTrace(runId: string): Promise<RunTrace> {
  let pending = read.get(runId)
  if (!pending) {
    pending = getJson<RunTrace>(`/api/runs/${encodeURIComponent(runId)}/trace`)
    read.set(runId, pending)
    // A failure is not kept: the next opening of the answer asks again.
    pending.catch(() => read.delete(runId))
  }
  return pending
}
