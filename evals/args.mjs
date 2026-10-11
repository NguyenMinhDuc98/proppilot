// The command line of run.mjs. Kept apart so it is tested without starting a run.

export const USAGE = `Usage: node evals/run.mjs [options]

  --url <base url>       backend to ask (default http://localhost:8080)
  --runs <n>             times each case is run, 1 to 20 (default 1)
  --only <prefixes>      comma-separated id prefixes: en- ar- write- rag- multi- turn- ambig- scope- inject- trap-
  --offline-only         skip the cases that only a real model can pass (offline: false)
  --judge                score tone, language and grounding with an LLM judge (needs ANTHROPIC_API_KEY;
                         model from EVAL_JUDGE_MODEL, default claude-haiku-4-5-20251001)
  --out <file>           results as JSON (default evals/results/latest.json)
  --summary <file>       results as markdown (default evals/results/latest.md)
  --delay-ms <ms>        pause after every request (default 0)
  --compare <file>       an earlier results file: print how this run differs
  --help                 this text`

const FLAGS = new Set(['offline-only', 'judge', 'help'])
const VALUES = new Set(['url', 'runs', 'only', 'out', 'summary', 'delay-ms', 'compare'])
const MOST_RUNS = 20

/**
 * @param {string[]} argv the arguments after the script name
 * @param {string} resultsDir where the default output files go
 * @returns {object} the options, or {error} for a command line that cannot be run
 */
export function parseArgs(argv, resultsDir) {
  const values = new Map()
  const flags = new Set()
  for (let i = 0; i < argv.length; i++) {
    const key = argv[i]
    if (!key.startsWith('--')) return { error: `unexpected argument ${key}` }
    const name = key.slice(2)
    if (FLAGS.has(name)) {
      flags.add(name)
    } else if (VALUES.has(name)) {
      const value = argv[i + 1]
      if (value === undefined || value.startsWith('--')) return { error: `--${name} needs a value` }
      values.set(name, value)
      i++
    } else {
      return { error: `unknown option ${key}` }
    }
  }
  const runs = Number(values.get('runs') ?? 1)
  if (!Number.isInteger(runs) || runs < 1 || runs > MOST_RUNS) {
    return { error: `--runs must be a whole number from 1 to ${MOST_RUNS}` }
  }
  const delayMs = Number(values.get('delay-ms') ?? 0)
  if (!Number.isFinite(delayMs) || delayMs < 0) return { error: '--delay-ms must be 0 or more' }
  const url = (values.get('url') ?? 'http://localhost:8080').replace(/\/$/, '')
  try {
    new URL(url)
  } catch {
    return { error: `--url is not a URL: ${url}` }
  }
  return {
    help: flags.has('help'),
    url,
    runs,
    only: (values.get('only') ?? '').split(',').map((prefix) => prefix.trim()).filter(Boolean),
    offlineOnly: flags.has('offline-only'),
    judge: flags.has('judge'),
    out: values.get('out') ?? `${resultsDir}/latest.json`,
    summary: values.get('summary') ?? `${resultsDir}/latest.md`,
    delayMs,
    compare: values.get('compare') ?? null,
  }
}
