import type { DonePayload } from '../api/types'

/**
 * Everything the model read for a run or a call. `inputTokens` leaves out what the prompt cache served or took in,
 * so with caching it can be a handful of tokens next to a cost that was worked out from thousands.
 */
export function totalInputTokens(
  usage: Pick<DonePayload, 'inputTokens' | 'cacheReadTokens' | 'cacheWriteTokens'>,
): number {
  return usage.inputTokens + usage.cacheReadTokens + usage.cacheWriteTokens
}
