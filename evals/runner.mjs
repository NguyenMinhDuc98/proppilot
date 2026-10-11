// Runs cases against a backend: asks each question the number of times asked for, in the conversation the case sets
// up, grades every reply and keeps what happened. The backend is a client (client.mjs) and the judge a function, so
// all of it is tested without a network (runner.test.mjs).

import { citedRefs, returnedRefs } from './citations.mjs'
import { categoryOf } from './cases.mjs'
import { grade } from './grade.mjs'
import { historyOf } from './history.mjs'
import { DEFAULT_RUBRICS } from './judge.mjs'

const MOST_JUDGED_SOURCES = 8
const MOST_JUDGED_SOURCE_LENGTH = 600

/** What the judge is asked to hold the answer to, or null when this case is not judged. */
export function rubricOf(item) {
  if (item.judge?.rubric) return item.judge.rubric
  if (item.refuses) return DEFAULT_RUBRICS.refuses
  return item.clarifies ? DEFAULT_RUBRICS.clarifies : null
}

/** What a reply cost and which model answered it: kept for every question of a conversation, not only the graded one. */
function usageOf(run) {
  const done = run.done ?? {}
  return {
    status: done.status ?? null,
    latencyMs: done.latencyMs ?? run.elapsedMs ?? null,
    costUsd: Number(done.costUsd ?? 0),
    inputTokens: done.inputTokens ?? 0,
    outputTokens: done.outputTokens ?? 0,
    cacheReadTokens: done.cacheReadTokens ?? 0,
    cacheWriteTokens: done.cacheWriteTokens ?? 0,
    provider: done.provider ?? null,
    model: done.model ?? null,
    routedReason: done.routedReason ?? null,
    guardrailStatus: done.guardrailStatus ?? null,
  }
}

function trialRecord(run, verdict, extra) {
  const { pass, reasons, ...findings } = verdict
  const returned = run.returnedRefs ?? []
  const cited = citedRefs(run.answer)
  return {
    pass,
    reasons,
    ...findings,
    ...usageOf(run),
    guardrailValues: run.unverified,
    tools: run.calls,
    proposed: run.proposals.map((proposal) => ({
      ...proposal, finalStatus: (run.readBack ?? []).find((read) => read.id === proposal.id)?.status ?? null,
    })),
    writeExecuted: (run.readBack ?? []).some((read) => read.status === 'APPROVED')
      || run.proposals.some((proposal) => proposal.status === 'APPROVED'),
    citations: { cited, returned, invented: cited.filter((ref) => !returned.includes(ref)) },
    answer: run.answer,
    preamble: run.preamble,
    judge: run.judge ?? null,
    error: run.error,
    ...extra,
  }
}

/** A trial that never got a reply: the request was refused or the connection failed. */
function failedTrial(error, priorTurns) {
  const reason = `the run could not be completed: ${error.message}`
  return {
    pass: false, reasons: [reason], status: null, latencyMs: null, costUsd: 0, inputTokens: 0, outputTokens: 0,
    cacheReadTokens: 0, cacheWriteTokens: 0, provider: null, model: null, routedReason: null, guardrailStatus: null,
    guardrailValues: [], tools: [], proposed: [], writeExecuted: false,
    citations: { cited: [], returned: [], invented: [] }, answer: '', preamble: '', judge: null, error: error.message,
    priorTurns,
  }
}

/**
 * Plays one case once: its earlier questions with the answers the assistant really gave, then its question, in that
 * conversation. Only the last reply is graded; an earlier turn that fails ends the trial.
 */
async function runTrial(item, context) {
  const { client, limits, judge, pause } = context
  const priorTurns = []
  const earlierSources = []
  const earlierProposals = []
  let history = []
  try {
    for (const question of item.priorQuestions ?? []) {
      const turn = await client.ask(question, history)
      if (turn.error || !turn.done) {
        throw new Error(`the earlier question "${question}" failed: ${turn.error ?? 'no reply'}`)
      }
      priorTurns.push({ question, answer: turn.answer, tools: turn.tools, proposals: turn.proposals, ...usageOf(turn) })
      earlierProposals.push(...turn.proposals)
      earlierSources.push(...turn.sources)
      history = historyOf(priorTurns, limits)
      await pause()
    }
    const run = await client.ask(item.question, history)
    if (!run.done && !run.error) run.error = 'the reply ended without a done event'
    run.priorTurns = priorTurns
    run.returnedRefs = returnedRefs([...earlierSources, ...run.sources])
    const proposed = [...earlierProposals, ...run.proposals]
    const read = await Promise.all(proposed.map(async ({ id }) => ({ id, status: await client.actionStatus(id) })))
    run.readBack = read.filter((action) => action.status !== null)
    let verdict = grade(item, run)
    const rubric = judge ? rubricOf(item) : null
    if (rubric) {
      const sources = [...earlierSources, ...run.sources].slice(0, MOST_JUDGED_SOURCES)
        .map(({ ref, text }) => ({ ref, text: String(text ?? '').slice(0, MOST_JUDGED_SOURCE_LENGTH) }))
      run.judge = await judge({
        question: item.question, lang: item.lang, rubric, answer: run.answer, calls: run.calls, sources,
      })
      verdict = grade(item, run)
    }
    return trialRecord(run, verdict, { priorTurns })
  } catch (error) {
    return failedTrial(error, priorTurns)
  }
}

/** An entry per case for `runCases` to add the trials of each run to. */
export function newResults(cases) {
  return cases.map((item) => ({
    id: item.id, category: categoryOf(item.id), lang: item.lang, question: item.question, trials: [],
  }))
}

/**
 * @param {object} config
 * @param {object[]} config.cases the cases to run
 * @param {number} config.runs how many times each case is run; the cases are run in sweeps: all of them once, then
 *   all of them again
 * @param {object} config.client see client.mjs
 * @param {{maxHistoryItems: number, maxHistoryTurnLength: number}} config.limits what the server accepts of a history
 * @param {((input: object) => Promise<object>)|null} config.judge scores an answer, or null for no judge
 * @param {number} [config.delayMs] pause after every request
 * @param {(ms: number) => Promise<void>} [config.sleep]
 * @param {(item: object, trial: object, run: number) => void} [config.onTrial]
 * @param {(results: object[], run: number) => (void|Promise<void>)} [config.onSweep] called when a sweep is done,
 *   with the trials so far, so a caller can save them before the next sweep starts
 * @param {object[]} [config.results] the entries to add the trials to (see `newResults`), for a caller that wants to
 *   read what has been run so far while it runs
 * @returns {Promise<{id: string, category: string, lang: string, question: string, trials: object[]}[]>}
 */
export async function runCases({
  cases, runs, client, limits, judge = null, delayMs = 0, sleep, onTrial = () => {}, onSweep = () => {},
  results = newResults(cases),
}) {
  const wait = sleep ?? ((ms) => new Promise((resolve) => setTimeout(resolve, ms)))
  const context = { client, limits, judge, pause: async () => { if (delayMs) await wait(delayMs) } }
  for (let run = 1; run <= runs; run++) {
    for (const [index, item] of cases.entries()) {
      const trial = await runTrial(item, context)
      results[index].trials.push({ run, ...trial })
      onTrial(item, trial, run)
      await context.pause()
    }
    await onSweep(results, run)
  }
  return results
}
