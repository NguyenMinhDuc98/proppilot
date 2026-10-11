// node --test evals/stream.test.mjs
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { grade } from './grade.mjs'
import { apply, newRun } from './stream.mjs'

/** Plays a list of [event, data] pairs into a new run. */
function play(...events) {
  const run = newRun()
  for (const [event, data] of events) apply(run, event, data)
  return run
}

test('the tokens add up to the answer, and the tools and the end of the reply are kept', () => {
  const run = play(
    ['tool_call', { name: 'get_unit_details' }],
    ['token', { text: 'The rent is ' }],
    ['token', { text: 'SAR 4,950.' }],
    ['done', { status: 'OK', guardrailStatus: 'OK' }],
  )

  assert.equal(run.answer, 'The rent is SAR 4,950.')
  assert.deepEqual(run.tools, ['get_unit_details'])
  assert.equal(run.done.guardrailStatus, 'OK')
  assert.equal(run.error, null)
})

test('an answer the server revised is graded as the new one alone', () => {
  const run = play(
    ['token', { text: 'The rent of A-203 is SAR 5,200 [LSE-A-203#1].' }],
    ['revise', { reason: 'grounding' }],
    ['token', { text: 'The rent of A-203 is SAR 4,800.' }],
    ['done', { status: 'OK', guardrailStatus: 'RETRIED_OK' }],
  )

  assert.equal(run.answer, 'The rent of A-203 is SAR 4,800.')
  assert.equal(run.done.guardrailStatus, 'RETRIED_OK')
  // what the discarded answer held does not pass the case, and what it got wrong does not fail it
  const item = { expect: ['4,800'], absent: ['5,200'], mustCite: 'LSE-A-203' }
  const verdict = grade(item, run)
  assert.deepEqual(verdict.missingCitations, ['LSE-A-203'])
  assert.deepEqual(verdict.unwantedFacts, [])
  assert.deepEqual(verdict.missingFacts, [])
})

test('the earlier answer sent again after a second revise is the answer', () => {
  const run = play(
    ['token', { text: 'The rent is SAR 5,200.' }],
    ['revise', { reason: 'grounding' }],
    ['token', { text: 'Let me check' }],
    ['tool_call', { name: 'get_unit_details' }],
    ['revise', { reason: 'grounding' }],
    ['token', { text: 'The rent is SAR 5,200.' }],
    ['guardrail', { status: 'unverified', values: [{ kind: 'NUMBER', value: '5,200' }] }],
    ['done', { status: 'OK', guardrailStatus: 'FLAGGED' }],
  )

  assert.equal(run.answer, 'The rent is SAR 5,200.')
  assert.deepEqual(run.unverified, [{ kind: 'NUMBER', value: '5,200' }])
  assert.equal(run.done.guardrailStatus, 'FLAGGED')
})

test('an error event is kept', () => {
  const run = play(['error', { message: 'The assistant is busy', code: 'llm_overloaded' }])

  assert.equal(run.error, 'The assistant is busy')
})

test('a tool call keeps its arguments and the text before it is the preamble, not the answer', () => {
  const run = play(
    ['token', { text: 'Let me look that up. ' }],
    ['tool_call', { name: 'search_units', args: { city: 'Riyadh', bedrooms: 3 } }],
    ['tool_result', { name: 'search_units', summary: '1 unit', error: false }],
    ['token', { text: 'Only B-205.' }],
    ['done', { status: 'OK' }],
  )

  assert.equal(run.answer, 'Only B-205.')
  assert.equal(run.preamble, 'Let me look that up.')
  assert.deepEqual(run.calls, [{ name: 'search_units', args: { city: 'Riyadh', bedrooms: 3 } }])
  assert.deepEqual(run.tools, ['search_units'])
  assert.deepEqual(run.results, [{ name: 'search_units', summary: '1 unit', error: false }])
})

test('a tool call without arguments has an empty object for them', () => {
  assert.deepEqual(play(['tool_call', { name: 'find_overdue_tenants' }]).calls, [{ name: 'find_overdue_tenants', args: {} }])
})

test('the clauses a lease search returned are kept, and only a lease search has any', () => {
  const run = play(
    ['tool_call', { name: 'search_lease_documents', args: {} }],
    ['tool_result', { name: 'search_lease_documents', summary: '2 clauses', error: false,
      sources: [{ ref: 'LSE-A-203#5', text: '5. Late payment' }, { ref: 'LSE-A-203#4', text: '4. Notice' }] }],
    ['tool_call', { name: 'get_unit_details', args: { unit_code: 'A-203' } }],
    ['tool_result', { name: 'get_unit_details', summary: 'Unit A-203', error: false }],
  )

  assert.deepEqual(run.sources.map((source) => source.ref), ['LSE-A-203#5', 'LSE-A-203#4'])
})

test('a proposed action is kept with its kind and the status it was announced with', () => {
  const run = play(['action_proposed', { id: 'a1', kind: 'RECORD_PAYMENT', status: 'PROPOSED', args: { amount: 4950 } }])

  assert.deepEqual(run.proposals, [{ id: 'a1', kind: 'RECORD_PAYMENT', status: 'PROPOSED' }])
})

test('an error event keeps its code', () => {
  const run = play(['error', { message: 'busy', code: 'llm_overloaded' }])

  assert.equal(run.errorCode, 'llm_overloaded')
})
