// The conversation a follow-up question is asked in: the earlier questions of the case and the answers the assistant
// really gave to them, cut to what the server accepts the way the web app cuts them (chat.ts, fitTurn).

/** What /api/info has not said yet: the server's own defaults. */
export const DEFAULT_LIMITS = { maxHistoryItems: 20, maxHistoryTurnLength: 4000 }

/** The limits the server reports in /api/info, or its defaults for what it does not report. */
export function limitsFrom(info) {
  return {
    maxHistoryItems: info?.maxHistoryItems ?? DEFAULT_LIMITS.maxHistoryItems,
    maxHistoryTurnLength: info?.maxHistoryTurnLength ?? DEFAULT_LIMITS.maxHistoryTurnLength,
  }
}

const isHighSurrogate = (code) => code >= 0xd800 && code <= 0xdbff

/**
 * An answer cut to a turn's longest length, at the end of a line when there is one in its second half, with an
 * ellipsis as the mark. What a follow-up needs is the beginning of the answer.
 */
export function fitTurn(text, maxLength) {
  if (text.length <= maxLength) return text
  const room = maxLength - 1
  const lineEnd = text.lastIndexOf('\n', room)
  if (lineEnd > maxLength / 2) return `${text.slice(0, lineEnd).trimEnd()}…`
  const end = isHighSurrogate(text.charCodeAt(room - 1)) ? room - 1 : room
  return `${text.slice(0, end).trimEnd()}…`
}

/**
 * The history for the next question.
 *
 * @param {{question: string, answer: string}[]} turns the exchanges so far, oldest first
 * @param {{maxHistoryItems: number, maxHistoryTurnLength: number}} limits
 * @returns {{role: 'user'|'assistant', text: string}[]} whole exchanges only: the oldest are dropped when there are
 *   more than the server takes
 */
export function historyOf(turns, limits = DEFAULT_LIMITS) {
  const exchanges = turns
    .filter((turn) => turn.answer.trim() !== '')
    .map((turn) => [
      { role: 'user', text: turn.question },
      { role: 'assistant', text: fitTurn(turn.answer, limits.maxHistoryTurnLength) },
    ])
  const most = Math.floor(limits.maxHistoryItems / 2)
  return exchanges.slice(Math.max(0, exchanges.length - most)).flat()
}
