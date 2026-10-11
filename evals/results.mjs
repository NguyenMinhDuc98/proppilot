// The results file of a run (format 2) and the reading of an earlier one, of this format or of the first one, which
// had a flat `results` array and one run per case.

import { aggregate, caseOutcome } from './aggregate.mjs'
import { categoryOf } from './cases.mjs'

export const FORMAT = 2

/**
 * @param {object} input
 * @param {string} input.ranAt ISO time of the start
 * @param {object} input.options what was asked for: runs, only, offlineOnly, judge, delayMs
 * @param {string} input.url the backend's base URL; only its host is kept
 * @param {object|null} input.info what /api/info said, or null
 * @param {object} input.judge {requested, enabled, model, reason}
 * @param {object[]} input.cases the cases that ran, each with its trials (see runner.mjs)
 * @param {object[]} input.skipped the cases `--offline-only` left out
 */
export function buildResults({ ranAt, options, url, info, judge, cases, skipped }) {
  return {
    format: FORMAT,
    ranAt,
    backend: {
      host: new URL(url).host,
      mode: info?.mode ?? null,
      provider: info?.provider ?? cases.flatMap((item) => item.trials).find((trial) => trial.provider)?.provider
        ?? null,
      model: info?.model ?? null,
      retrieval: info?.retrieval ?? null,
      leaseDocuments: info?.leaseDocuments ?? null,
    },
    options,
    judge,
    skipped: skipped.map((item) => item.id),
    aggregate: aggregate(cases, options.runs),
    cases: cases.map((item) => ({ ...item, ...caseOutcome(item.trials) })),
  }
}

/**
 * An earlier results file as a format 2 document, enough of one to compare with: its aggregate and, for each case,
 * how many runs passed. The first format's `results` array becomes cases of one run each.
 */
export function readPrevious(document) {
  if (document?.format === FORMAT) return document
  if (!Array.isArray(document?.results)) throw new Error('not a results file of this runner')
  const cases = document.results.map((result) => ({
    id: result.id,
    category: categoryOf(result.id),
    lang: result.lang,
    trials: [{
      pass: Boolean(result.pass), latencyMs: result.latencyMs ?? null, costUsd: result.costUsd ?? 0, model: result.model,
    }],
  }))
  return {
    format: FORMAT,
    ranAt: document.ranAt,
    aggregate: aggregate(cases, 1),
    cases: cases.map((item) => ({ ...item, ...caseOutcome(item.trials) })),
  }
}
