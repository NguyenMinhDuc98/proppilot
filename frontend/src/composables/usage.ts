import type { DonePayload } from '../api/types'

/**
 * Everything the model read for the run. The done event's `inputTokens` leaves out what the prompt cache served or
 * took in, so with caching it can be a handful of tokens next to a cost that was worked out from thousands.
 */
export function totalInputTokens(usage: DonePayload): number {
  return usage.inputTokens + usage.cacheReadTokens + usage.cacheWriteTokens
}
