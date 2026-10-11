// Checks of the arguments the model chose for a tool call (the `toolArgs` field of a case).

import { normalize } from './text.mjs'

/**
 * Whether one argument holds what a case expects of it.
 *
 *  - an array of expectations is a list of alternatives, of which any one counts;
 *  - a number is equal to the argument, which may be a number or a number written as text (4950, "4,950.00");
 *  - a boolean is equal to the argument;
 *  - a string is contained in the argument, ignoring case and number formatting (the model may send the
 *    tenant's whole name where the case names the surname). A list argument holds it when any of its entries does.
 */
export function argumentMatches(actual, expected) {
  if (Array.isArray(expected)) return expected.some((alternative) => argumentMatches(actual, alternative))
  if (Array.isArray(actual)) return actual.some((entry) => argumentMatches(entry, expected))
  if (actual === undefined || actual === null) return false
  if (typeof expected === 'number') return Number(normalize(actual)) === expected
  if (typeof expected === 'boolean') return actual === expected
  return normalize(actual).includes(normalize(expected))
}

/** Whether a call of the tool has every argument of `match`. */
export function callMatches(call, tool, match) {
  return call.name === tool
    && Object.entries(match).every(([field, expected]) => argumentMatches(call.args?.[field], expected))
}

/** The entries of `toolArgs` that no call of the run satisfies. */
export function unmetToolArgs(expectations, calls) {
  return expectations.filter(({ tool, match }) => !calls.some((call) => callMatches(call, tool, match)))
}
