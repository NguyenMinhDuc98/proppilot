// node --test evals/text.test.mjs
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { compact, fold, mentionsAny, normalize, unifySpelling } from './text.mjs'

test('numbers lose their thousands separators and their Arabic digits', () => {
  assert.equal(normalize('SAR 4,800 / ٤٨٠٠ / ٤٬٨٠٠'), 'sar 4800 / 4800 / 4800')
  assert.equal(normalize('1,234,567'), '1234567')
  assert.equal(normalize('4 800 and 4 800'), '4800 and 4800')
})

test('what only looks like a separator is left alone', () => {
  assert.equal(normalize('1,2345'), '1,2345')
  assert.equal(normalize('A-203, 300 SAR'), 'a-203, 300 sar')
  assert.equal(normalize('4800.50'), '4800.50')
  assert.equal(normalize('2 100'), '2 100')
})

test('a separator before a unit written next to the number is still one', () => {
  assert.equal(normalize('4,800sar'), '4800sar')
})

test('Arabic is compared without vowel marks, tatweel and the variants of alef, ya and ta marbuta', () => {
  assert.equal(fold('لَمْ يُدْفَع'), fold('لم يدفع'))
  assert.equal(fold('أحمد'), fold('احمد'))
  assert.equal(fold('إيجار'), fold('ايجار'))
  assert.equal(fold('مبنى'), fold('مبني'))
  assert.equal(fold('الدفعة'), fold('الدفعه'))
  assert.equal(fold('عـــذرا'), fold('عذرا'))
})

test('apostrophes are straight', () => {
  assert.equal(fold('I can’t'), "i can't")
})

test('spelling is unified without lowering case or touching numbers, so a pattern can be read the same way', () => {
  assert.equal(unifySpelling("I’ve A-1,000 \\bأحمد\\b"), "I've A-1,000 \\bاحمد\\b")
  assert.equal(unifySpelling('الدفعةُ'), 'الدفعه')
  assert.equal(fold("I’VE ٤٬٨٠٠"), "i've 4800")
})

test('mentionsAny folds both sides', () => {
  assert.equal(mentionsAny("I CAN’T do that", ["can't"]), true)
  assert.equal(mentionsAny('عُذراً', ['عذرا']), true)
  assert.equal(mentionsAny('fine', ["can't"]), false)
})

test('a unit code is compared without its hyphen, case and spaces', () => {
  assert.equal(compact('A-203'), compact('a 203'))
  assert.equal(compact('B-101'), 'b101')
})
