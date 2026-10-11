// node --test evals/args.test.mjs
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { parseArgs } from './args.mjs'

const dir = '/repo/evals/results'

test('no argument is the local backend, one run, every case and the default files', () => {
  assert.deepEqual(parseArgs([], dir), {
    help: false, url: 'http://localhost:8080', runs: 1, only: [], offlineOnly: false, judge: false,
    out: '/repo/evals/results/latest.json', summary: '/repo/evals/results/latest.md', delayMs: 0, compare: null,
  })
})

test('every option is read', () => {
  const options = parseArgs(['--url', 'https://api.example.test/', '--runs', '3', '--only', 'en-, ar- ,rag-', '--offline-only',
    '--judge', '--out', 'a.json', '--summary', 'a.md', '--delay-ms', '7000', '--compare', 'old.json'], dir)

  assert.deepEqual(options, {
    help: false, url: 'https://api.example.test', runs: 3, only: ['en-', 'ar-', 'rag-'], offlineOnly: true, judge: true,
    out: 'a.json', summary: 'a.md', delayMs: 7000, compare: 'old.json',
  })
})

test('a bad command line is an error that says what is wrong', () => {
  assert.match(parseArgs(['--runs', '0'], dir).error, /--runs/)
  assert.match(parseArgs(['--runs', '2.5'], dir).error, /--runs/)
  assert.match(parseArgs(['--runs', '21'], dir).error, /--runs/)
  assert.match(parseArgs(['--runs', 'many'], dir).error, /--runs/)
  assert.match(parseArgs(['--delay-ms', '-1'], dir).error, /--delay-ms/)
  assert.match(parseArgs(['--url', 'not a url'], dir).error, /--url/)
  assert.match(parseArgs(['--bogus'], dir).error, /unknown option --bogus/)
  assert.match(parseArgs(['en-'], dir).error, /unexpected argument en-/)
  assert.match(parseArgs(['--out'], dir).error, /--out needs a value/)
  assert.match(parseArgs(['--out', '--judge'], dir).error, /--out needs a value/)
})

test('help is a flag', () => {
  assert.equal(parseArgs(['--help'], dir).help, true)
})
