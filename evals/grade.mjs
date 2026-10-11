// How one answer is graded against its case in questions.json. Kept apart from run.mjs so it can be tested without a
// backend (grade.test.mjs).

/** Lower case, and without number formatting: 4,800 = 4800 = ٤٨٠٠, because models format numbers freely. */
export function normalize(text) {
  return String(text)
    .toLowerCase()
    .replace(/[٠-٩]/g, (digit) => String(digit.charCodeAt(0) - 0x0660))
    .replace(/(\d),(?=\d{3}\b)/g, '$1')
}

/**
 * Whether the answer holds a citation marker of the lease document with this code: its code, '#', a clause number and
 * optionally the lowercase letters of a part, in square brackets ([LSE-A-504#7], [LSE-A-504#7a]). The pattern has no
 * unbounded group, so an answer of any length is read in time proportional to its size.
 */
export function cites(answer, documentCode) {
  const code = documentCode.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  return new RegExp(`\\[${code}#(?:0|[1-9][0-9]?)[a-z]{0,4}\\]`).test(answer)
}

/**
 * A case passes when the run ended without an error and
 *  - every `expect` entry is in the answer, and no `absent` entry is (an entry is a string, or an array of
 *    alternative wordings of which any one counts);
 *  - every tool in `tools` was called, and none of `absentTools` was;
 *  - the answer cites every document code in `mustCite` (a code, or an array of them).
 */
export function grade(item, run) {
  const answer = normalize(run.answer)
  const present = (fact) => [fact].flat().some((alternative) => answer.includes(normalize(alternative)))
  const missingFacts = item.expect.filter((fact) => !present(fact))
  const unwantedFacts = (item.absent ?? []).filter(present)
  const missingTools = (item.tools ?? []).filter((tool) => !run.tools.includes(tool))
  const forbiddenToolsCalled = (item.absentTools ?? []).filter((tool) => run.tools.includes(tool))
  const missingCitations = [item.mustCite ?? []].flat().filter((code) => !cites(run.answer, code))
  const pass = !run.error && missingFacts.length === 0 && unwantedFacts.length === 0 && missingTools.length === 0
    && forbiddenToolsCalled.length === 0 && missingCitations.length === 0
  return { missingFacts, unwantedFacts, missingTools, forbiddenToolsCalled, missingCitations, pass }
}
