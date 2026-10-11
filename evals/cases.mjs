// The cases of questions.json: what they are called, which of them a run takes.

/** What each id prefix stands for. */
const CATEGORIES = {
  en: 'read',
  ar: 'read',
  write: 'write',
  rag: 'lease',
  multi: 'multi-tool',
  turn: 'multi-turn',
  ambig: 'ambiguous',
  scope: 'out-of-scope',
  inject: 'injection',
  trap: 'hallucination',
}

/** The category of a case, from the prefix of its id (en-01 is a read question, inject-02 an injection). */
export function categoryOf(id) {
  return CATEGORIES[id.replace(/-\d+$/, '')] ?? 'other'
}

/** The names of the categories, in the order a report lists them. */
export const CATEGORY_ORDER = ['read', 'write', 'lease', 'multi-tool', 'multi-turn', 'ambiguous', 'out-of-scope',
  'injection', 'hallucination', 'other']

/**
 * The cases a run takes: those whose id starts with one of the prefixes (all when there are none), without the ones
 * only real Claude can pass when `offlineOnly` is set. A case is expected to pass offline unless it says otherwise.
 *
 * @returns {{selected: object[], skipped: object[]}} `skipped` are the cases `offlineOnly` left out
 */
export function selectCases(cases, { only = [], offlineOnly = false } = {}) {
  const named = only.length ? cases.filter((item) => only.some((prefix) => item.id.startsWith(prefix))) : cases
  const skipped = offlineOnly ? named.filter((item) => item.offline === false) : []
  return { selected: named.filter((item) => !skipped.includes(item)), skipped }
}
