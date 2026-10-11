// node --test evals/grade.test.mjs
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'
import { fileURLToPath } from 'node:url'
import { cites, grade, normalize } from './grade.mjs'

const run = (answer, tools = [], error = null) => ({ answer, tools, error })

test('numbers are compared without their formatting', () => {
  assert.equal(normalize('SAR 4,800 / ٤٨٠٠'), 'sar 4800 / 4800')
  assert.equal(normalize('1,2345'), '1,2345')
})

test('a case without the lease fields is graded as it always was', () => {
  const item = { expect: ['4800', ['UNPAID', 'غير مدفوع']], absent: ['error'], tools: ['get_unit_details'] }

  assert.equal(grade(item, run('Rent is SAR 4,800. Status: UNPAID', ['get_unit_details'])).pass, true)

  const verdict = grade(item, run('Rent is SAR 4,900 and error', ['search_units']))
  assert.equal(verdict.pass, false)
  assert.deepEqual(verdict.missingFacts, ['4800', ['UNPAID', 'غير مدفوع']])
  assert.deepEqual(verdict.unwantedFacts, ['error'])
  assert.deepEqual(verdict.missingTools, ['get_unit_details'])
  assert.deepEqual(verdict.missingCitations, [])
  assert.deepEqual(verdict.forbiddenToolsCalled, [])
})

test('an answer that ended in an error fails whatever it says', () => {
  assert.equal(grade({ expect: [] }, run('fine', [], 'boom')).pass, false)
})

test('mustCite wants a marker of the document, with a clause number and optionally a part', () => {
  assert.equal(cites('The fee is SAR 75 a day [LSE-A-203#5].', 'LSE-A-203'), true)
  assert.equal(cites('Cited twice [LSE-B-1#3][LSE-A-203#7a].', 'LSE-A-203'), true)
  assert.equal(cites('The preamble [LSE-A-203#0]', 'LSE-A-203'), true)
  assert.equal(cites('بند [LSE-A-203#12] غرامة', 'LSE-A-203'), true)
})

test('what only looks like a citation of the document is not one', () => {
  for (const answer of [
    'See LSE-A-203#5 for the fee',
    'See lease LSE-A-203 for the fee',
    'The fee is in [LSE-A-203]',
    'The fee is in [LSE-A-203#]',
    'The fee is in [LSE-A-203#05]',
    'The fee is in [LSE-A-203#7A]',
    'The fee is in [LSE-A-203#7abcde]',
    'The fee is in [lse-a-203#5]',
    'The fee is in [LSE-A-2030#5]',
    'The fee is in [XLSE-A-203#5]',
    'The fee is in [LSE-B-203#5]',
    'The fee is in (LSE-A-203#5)',
  ]) {
    assert.equal(cites(answer, 'LSE-A-203'), false, answer)
  }
})

test('a document code is matched as text, not as a pattern', () => {
  assert.equal(cites('[LSEXA-203#5]', 'LSE.A-203'), false)
  assert.equal(cites('[LSE.A-203#5]', 'LSE.A-203'), true)
})

test('mustCite takes one code or a list and names the documents the answer does not cite', () => {
  const answer = run('Notice is 90 days [LSE-B-501#4]. A-204 also needs 90.', ['search_lease_documents'])

  assert.deepEqual(grade({ expect: [], mustCite: 'LSE-B-501' }, answer).missingCitations, [])
  const verdict = grade({ expect: [], mustCite: ['LSE-B-501', 'LSE-A-204'] }, answer)
  assert.deepEqual(verdict.missingCitations, ['LSE-A-204'])
  assert.equal(verdict.pass, false)
})

test('a citation is required even when every fact is there', () => {
  const item = { expect: ['90'], mustCite: 'LSE-B-501' }

  assert.equal(grade(item, run('The notice period is 90 days.')).pass, false)
  assert.equal(grade(item, run('The notice period is 90 days [LSE-B-501#4].')).pass, true)
})

test('absentTools fails a run that called one of them, however the answer reads', () => {
  const item = { expect: ['parking'], absentTools: ['record_payment', 'send_tenant_reminder'] }

  assert.equal(grade(item, run('parking', ['search_lease_documents'])).pass, true)
  const verdict = grade(item, run('parking', ['search_lease_documents', 'record_payment']))
  assert.equal(verdict.pass, false)
  assert.deepEqual(verdict.forbiddenToolsCalled, ['record_payment'])
})

test('a very long answer is read quickly', () => {
  const hostile = ['[LSE-A-203#', '[', '[LSE-A-203#1', '[LSE-A-203#aaaa'].map((unit) => unit.repeat(500_000))

  const started = Date.now()
  for (const answer of hostile) assert.equal(cites(answer, 'LSE-A-203'), false)
  assert.ok(Date.now() - started < 2000)
})

test('every case of questions.json is complete and the lease cases check their citations', () => {
  const file = fileURLToPath(new URL('./questions.json', import.meta.url))
  const cases = JSON.parse(readFileSync(file, 'utf8'))
  const ids = new Set()

  for (const item of cases) {
    assert.ok(!ids.has(item.id), `${item.id} is used twice`)
    ids.add(item.id)
    assert.ok(['en', 'ar'].includes(item.lang), `${item.id}: lang`)
    assert.ok(typeof item.question === 'string' && item.question.length > 0, `${item.id}: question`)
    assert.ok(Array.isArray(item.tools) && item.tools.length > 0, `${item.id}: tools`)
    assert.ok(Array.isArray(item.expect) && item.expect.length > 0, `${item.id}: expect`)
    for (const code of [item.mustCite ?? []].flat()) {
      assert.match(code, /^LSE-[A-Z]{1,8}-[0-9]{1,5}$/, `${item.id}: mustCite`)
    }
  }

  const lease = cases.filter((item) => item.id.startsWith('rag-'))
  assert.ok(lease.length > 0)
  for (const item of lease) {
    const saysNoDocument = (item.absent ?? []).includes('[LSE-')
    assert.ok(saysNoDocument || [item.mustCite ?? []].flat().length > 0, `${item.id}: cites nothing`)
  }
  assert.equal(lease.filter((item) => item.lang === 'en').length, lease.filter((item) => item.lang === 'ar').length)
})
