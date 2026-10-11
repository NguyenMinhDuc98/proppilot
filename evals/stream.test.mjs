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
