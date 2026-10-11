// node --test evals/history.test.mjs
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { DEFAULT_LIMITS, fitTurn, historyOf, limitsFrom } from './history.mjs'

const turn = (n, answer = `answer ${n}`) => ({ question: `question ${n}`, answer })

test('the history is the earlier questions and the answers that were given, oldest first', () => {
  const history = historyOf([turn(1), turn(2)])

  assert.deepEqual(history, [
    { role: 'user', text: 'question 1' }, { role: 'assistant', text: 'answer 1' },
    { role: 'user', text: 'question 2' }, { role: 'assistant', text: 'answer 2' },
  ])
})

test('no earlier turn is no history', () => {
  assert.deepEqual(historyOf([]), [])
})

test('an exchange whose answer is empty is left out, so a question never stands without its answer', () => {
  assert.deepEqual(historyOf([turn(1, '  '), turn(2)]).map((t) => t.text), ['question 2', 'answer 2'])
})

test('the oldest exchanges are dropped when the server takes fewer items', () => {
  const history = historyOf([turn(1), turn(2), turn(3)], { maxHistoryItems: 4, maxHistoryTurnLength: 100 })

  assert.deepEqual(history.map((t) => t.text), ['question 2', 'answer 2', 'question 3', 'answer 3'])
  assert.deepEqual(historyOf([turn(1)], { maxHistoryItems: 1, maxHistoryTurnLength: 100 }), [])
})

test('a long answer is cut to what a turn may hold, at a line end when there is one in its second half', () => {
  const answer = `${'a'.repeat(60)}\n${'b'.repeat(60)}`

  assert.equal(fitTurn('short', 100), 'short')
  assert.equal(fitTurn(answer, 100), `${'a'.repeat(60)}…`)
  assert.equal(fitTurn('c'.repeat(200), 100), `${'c'.repeat(99)}…`)
  assert.ok(historyOf([turn(1, 'd'.repeat(5000))])[1].text.length <= DEFAULT_LIMITS.maxHistoryTurnLength)
})

test('a character of two code units is not cut in half', () => {
  const cut = fitTurn(`${'a'.repeat(98)}😀😀😀`, 100)

  assert.ok(!/[\ud800-\udbff]…$/.test(cut))
  assert.ok(cut.length <= 100)
})

test('the limits are the server\'s, or its defaults for what it does not report', () => {
  assert.deepEqual(limitsFrom({ maxHistoryItems: 6, maxHistoryTurnLength: 500 }), { maxHistoryItems: 6, maxHistoryTurnLength: 500 })
  assert.deepEqual(limitsFrom(null), DEFAULT_LIMITS)
  assert.deepEqual(limitsFrom({ maxHistoryItems: 6 }), { maxHistoryItems: 6, maxHistoryTurnLength: 4000 })
})
