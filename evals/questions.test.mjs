// node --test evals/questions.test.mjs
// The shape of questions.json: a typo in a field name or a tool name would make a check silently do nothing.
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'
import { fileURLToPath } from 'node:url'
import { categoryOf, selectCases } from './cases.mjs'
import { grade } from './grade.mjs'

const cases = JSON.parse(readFileSync(fileURLToPath(new URL('./questions.json', import.meta.url)), 'utf8'))

const FIELDS = new Set(['id', 'lang', 'question', 'priorQuestions', 'expect', 'absent', 'forbid', 'tools', 'absentTools',
  'toolArgs', 'mustCite', 'proposes', 'refuses', 'clarifies', 'noGuardrailFlag', 'judge', 'offline'])
const CHECKS = ['expect', 'absent', 'forbid', 'tools', 'absentTools', 'toolArgs', 'mustCite', 'proposes', 'refuses',
  'clarifies', 'noGuardrailFlag', 'judge']
const TOOLS = new Set(['search_units', 'get_unit_details', 'find_overdue_tenants', 'get_occupancy_summary',
  'get_payment_history', 'draft_tenant_message', 'record_payment', 'create_maintenance_request', 'send_tenant_reminder',
  'search_lease_documents', 'find_expiring_leases'])
const WRITE_TOOLS = ['record_payment', 'create_maintenance_request', 'send_tenant_reminder']
const ACTION_KINDS = new Set(['RECORD_PAYMENT', 'CREATE_MAINTENANCE_REQUEST', 'SEND_TENANT_REMINDER'])
const MOST_MESSAGE_LENGTH = 500

/** The first 42 cases, which the later ones were added after. */
const LEGACY_IDS = [
  ...Array.from({ length: 10 }, (_, i) => `en-${String(i + 1).padStart(2, '0')}`),
  ...Array.from({ length: 10 }, (_, i) => `ar-${String(i + 1).padStart(2, '0')}`),
  ...Array.from({ length: 6 }, (_, i) => `write-${String(i + 1).padStart(2, '0')}`),
  ...Array.from({ length: 16 }, (_, i) => `rag-${String(i + 1).padStart(2, '0')}`),
]

const isWords = (value) => typeof value === 'string' || (Array.isArray(value) && value.length > 0 && value.every((v) => typeof v === 'string'))
const isEntry = (value) => isWords(value) || (typeof value?.pattern === 'string' && Object.keys(value).every((k) => ['pattern', 'flags'].includes(k)))

test('ids are unique, and every field of every case is a known one of the right type', () => {
  const ids = new Set()
  for (const item of cases) {
    assert.ok(!ids.has(item.id), `${item.id} is used twice`)
    ids.add(item.id)
    for (const key of Object.keys(item)) assert.ok(FIELDS.has(key), `${item.id}: unknown field ${key}`)
    assert.ok(['en', 'ar'].includes(item.lang), `${item.id}: lang`)
    assert.ok(typeof item.question === 'string' && item.question.trim() !== '', `${item.id}: question`)
    assert.ok(item.question.length <= MOST_MESSAGE_LENGTH, `${item.id}: question is longer than the server takes`)
    assert.notEqual(categoryOf(item.id), 'other', `${item.id}: its prefix is no category`)
    for (const earlier of item.priorQuestions ?? []) {
      assert.ok(typeof earlier === 'string' && earlier.length <= MOST_MESSAGE_LENGTH, `${item.id}: priorQuestions`)
    }
    for (const field of ['expect', 'absent', 'forbid']) {
      const entries = item[field] ?? []
      assert.ok(Array.isArray(entries), `${item.id}: ${field} is a list`)
      assert.ok(entries.every(field === 'expect' ? isWords : isEntry), `${item.id}: an entry of ${field}`)
    }
    for (const field of ['tools', 'absentTools']) {
      for (const tool of item[field] ?? []) assert.ok(TOOLS.has(tool), `${item.id}: ${field} names ${tool}`)
    }
    for (const entry of item.toolArgs ?? []) {
      assert.ok(TOOLS.has(entry.tool), `${item.id}: toolArgs names ${entry.tool}`)
      assert.ok(entry.match && Object.keys(entry.match).length > 0, `${item.id}: toolArgs match`)
    }
    for (const kind of [item.proposes ?? []].flat()) {
      if (kind !== false) assert.ok(ACTION_KINDS.has(kind), `${item.id}: proposes ${kind}`)
    }
    for (const code of [item.mustCite ?? []].flat()) assert.match(code, /^LSE-[A-Z]{1,8}-[0-9]{1,5}$/, `${item.id}: mustCite`)
    if (item.judge) assert.ok(typeof item.judge.rubric === 'string' && item.judge.rubric.length > 20, `${item.id}: judge rubric`)
    if (item.offline !== undefined) assert.equal(typeof item.offline, 'boolean', `${item.id}: offline`)
  }
})

test('the first 42 cases are all there, each with its tools and its facts', () => {
  const byId = new Map(cases.map((item) => [item.id, item]))

  for (const id of LEGACY_IDS) {
    const item = byId.get(id)
    assert.ok(item, `${id} is missing`)
    assert.ok(Array.isArray(item.tools) && item.tools.length > 0, `${id}: tools`)
    assert.ok(Array.isArray(item.expect) && item.expect.length > 0, `${id}: expect`)
    assert.notEqual(item.offline, false, `${id}: the first cases are expected to pass offline`)
  }
  assert.deepEqual(cases.slice(0, LEGACY_IDS.length).map((item) => item.id), LEGACY_IDS)
})

test('the cases are counted by category, and each category is asked in both languages', () => {
  const counts = {}
  const languages = {}
  for (const item of cases) {
    const category = categoryOf(item.id)
    counts[category] = (counts[category] ?? 0) + 1
    languages[category] = new Set([...(languages[category] ?? []), item.lang])
  }

  assert.deepEqual(counts, {
    read: 20, write: 8, lease: 16, 'multi-tool': 3, 'multi-turn': 4, ambiguous: 3, 'out-of-scope': 4, injection: 4,
    hallucination: 2,
  })
  assert.equal(cases.length, 64)
  for (const [category, set] of Object.entries(languages)) assert.equal(set.size, 2, `${category} is in one language only`)
})

/** Whether the case sets the check; `proposes: false` is one (nothing may be proposed). */
function sets(item, field) {
  const value = item[field]
  if (field === 'proposes') return value !== undefined
  return Array.isArray(value) ? value.length > 0 : Boolean(value)
}

test('every case that the offline assistant is expected to pass has a check', () => {
  for (const item of cases.filter((c) => c.offline !== false)) {
    assert.ok(CHECKS.some((field) => sets(item, field)), `${item.id} checks nothing`)
  }
})

test('the cases the offline assistant cannot pass are marked, and are only among the later cases', () => {
  const marked = cases.filter((item) => item.offline === false).map((item) => item.id)

  assert.ok(marked.length > 0)
  for (const id of marked) assert.ok(!LEGACY_IDS.includes(id), `${id} was a case that passed offline`)
})

test('the lease cases cite a clause or say there is no document, and are as many in each language', () => {
  const lease = cases.filter((item) => categoryOf(item.id) === 'lease')

  for (const item of lease) {
    const saysNoDocument = (item.absent ?? []).includes('[LSE-')
    assert.ok(saysNoDocument || [item.mustCite ?? []].flat().length > 0, `${item.id}: cites nothing`)
  }
  assert.equal(lease.filter((item) => item.lang === 'en').length, lease.filter((item) => item.lang === 'ar').length)
})

test('a refusal calls no tool, a clarification names two candidates, an injection case forbids the writes', () => {
  for (const item of cases) {
    if (item.refuses) assert.equal((item.tools ?? []).length, 0, `${item.id}: refuses and expects a tool`)
    if (item.clarifies) {
      assert.ok(item.clarifies.length >= 2, `${item.id}: clarifies names fewer than two candidates`)
      assert.ok(item.clarifies.every(isWords), `${item.id}: a candidate`)
    }
    if (categoryOf(item.id) === 'injection') {
      for (const write of WRITE_TOOLS) assert.ok((item.absentTools ?? []).includes(write), `${item.id}: ${write} is not forbidden`)
      assert.equal(item.proposes, false, `${item.id}: proposes`)
    }
  }
})

test('a pattern of a case compiles, repeats no group without a limit and reads a hostile answer quickly', () => {
  const patterns = cases.flatMap((item) => [...(item.absent ?? []), ...(item.forbid ?? [])]).filter((entry) => entry.pattern)
  const hostile = ['payments has been ', 'i have ', '(((', 'تم تسجيل ', 'no payment '].map((unit) => unit.repeat(200_000))

  assert.ok(patterns.length > 0)
  for (const { pattern, flags } of patterns) {
    assert.doesNotThrow(() => new RegExp(pattern, flags ?? 'u'), pattern)
    assert.ok(!/\)(?:[*+]|\{\d+,\})/.test(pattern), `${pattern}: a group that repeats without a limit`)
    const regex = new RegExp(pattern, flags ?? 'u')
    const started = Date.now()
    for (const text of hostile) regex.test(text)
    assert.ok(Date.now() - started < 2000, `${pattern} is slow on a long answer`)
  }
})

test('a case with an empty answer is graded without failing on a missing field', () => {
  for (const item of cases) {
    assert.doesNotThrow(() => grade(item, { answer: '', tools: [], error: null }), item.id)
  }
})

test('the id of a case says its category, and --only and --offline-only pick cases by id and by flag', () => {
  assert.equal(categoryOf('en-03'), 'read')
  assert.equal(categoryOf('ar-10'), 'read')
  assert.equal(categoryOf('rag-16'), 'lease')
  assert.equal(categoryOf('inject-02'), 'injection')
  assert.equal(categoryOf('mystery-1'), 'other')

  const all = [{ id: 'en-01' }, { id: 'turn-01', offline: false }, { id: 'rag-01', offline: true }, { id: 'scope-01' }]
  assert.deepEqual(selectCases(all).selected.map((c) => c.id), ['en-01', 'turn-01', 'rag-01', 'scope-01'])
  assert.deepEqual(selectCases(all, { only: ['en-', 'turn-'] }).selected.map((c) => c.id), ['en-01', 'turn-01'])
  const offline = selectCases(all, { offlineOnly: true })
  assert.deepEqual(offline.selected.map((c) => c.id), ['en-01', 'rag-01', 'scope-01'])
  assert.deepEqual(offline.skipped.map((c) => c.id), ['turn-01'])
  assert.deepEqual(selectCases(all, { only: ['turn-'], offlineOnly: true }).selected, [])
})
