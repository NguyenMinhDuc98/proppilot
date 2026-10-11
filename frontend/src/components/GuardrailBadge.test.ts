import { mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import { afterEach, describe, expect, it } from 'vitest'
import type { GuardrailStatus, UnverifiedValue } from '../api/types'
import { i18n } from '../i18n'
import ar from '../i18n/ar'
import en from '../i18n/en'
import GuardrailBadge from './GuardrailBadge.vue'

const values: UnverifiedValue[] = [
  { kind: 'NUMBER', value: 'SAR 4,950' },
  { kind: 'UNIT_CODE', value: 'A-999' },
  { kind: 'NUMBER', value: 'SAR 4,950' },
  { kind: 'TENANT_NAME', value: 'أحمد الحربي' },
  { kind: 'CITATION', value: '[LSE-A-203#9]' },
]

function render(status: GuardrailStatus | null, shown: UnverifiedValue[] = values) {
  return mount(GuardrailBadge, { props: { status, values: shown }, global: { plugins: [i18n] }, attachTo: document.body })
}

afterEach(() => {
  document.body.innerHTML = ''
  i18n.global.locale.value = 'en'
})

describe('GuardrailBadge', () => {
  it.each(['FLAGGED', 'RETRIED_FLAGGED'] as const)('warns, as a status and in words, for an answer that is %s', (status) => {
    const wrapper = render(status)

    const region = wrapper.get('[role="status"]')
    expect(region.text()).toBe(en.guardrail.flagged)
    expect(region.get('svg').attributes('aria-hidden')).toBe('true')
    expect(wrapper.find('.rechecked').exists()).toBe(false)
  })

  it('keeps the values closed until the badge is pressed, and says so to assistive technology', async () => {
    const wrapper = render('FLAGGED')
    const badge = wrapper.get('button.badge')
    const panel = () => wrapper.get('.popover')

    expect(badge.attributes('aria-expanded')).toBe('false')
    expect(panel().isVisible()).toBe(false)
    expect(badge.attributes('aria-controls')).toBe(panel().attributes('id'))

    await badge.trigger('click')
    expect(badge.attributes('aria-expanded')).toBe('true')
    expect(panel().isVisible()).toBe(true)

    await badge.trigger('click')
    expect(panel().isVisible()).toBe(false)
  })

  it('lists each value once with what kind of value it is, and points to the trace', () => {
    const wrapper = render('FLAGGED')

    const items = wrapper.findAll('.popover li').map((item) => item.text())
    expect(items).toEqual([
      `${en.guardrail.kinds.NUMBER}SAR 4,950`,
      `${en.guardrail.kinds.UNIT_CODE}A-999`,
      `${en.guardrail.kinds.TENANT_NAME}أحمد الحربي`,
      `${en.guardrail.kinds.CITATION}[LSE-A-203#9]`,
    ])
    expect(wrapper.get('.popover').text()).toContain(en.guardrail.hint)
    expect(en.guardrail.hint).toContain(en.trace.toggle)
  })

  it('isolates every value so codes and amounts keep their order inside right-to-left text', () => {
    const wrapper = render('FLAGGED')

    const isolated = wrapper.findAll('.popover li bdi').map((item) => item.text())
    expect(isolated).toEqual(['SAR 4,950', 'A-999', 'أحمد الحربي', '[LSE-A-203#9]'])
  })

  it('is written in Arabic when the page is', async () => {
    const wrapper = render('FLAGGED')

    i18n.global.locale.value = 'ar'
    await nextTick()

    expect(wrapper.get('[role="status"]').text()).toBe(ar.guardrail.flagged)
    expect(wrapper.get('.popover').text()).toContain(ar.guardrail.intro)
    expect(wrapper.get('.popover').text()).toContain(ar.guardrail.hint)
  })

  it('shows only the note for an answer that was checked again and now matches', () => {
    const wrapper = render('RETRIED_OK', [])

    const note = wrapper.get('.rechecked')
    expect(note.text()).toBe(en.guardrail.rechecked)
    expect(note.attributes('role')).toBe('status')
    expect(note.attributes('title')).toBe(en.guardrail.recheckedHint)
    expect(wrapper.find('button').exists()).toBe(false)
  })

  it.each([['OK'], [null]] as const)('shows nothing for an answer whose verdict is %s', (status) => {
    const wrapper = render(status, [])

    expect(wrapper.html()).toBe('<!--v-if-->')
  })

  it('lists no values when the server sent none, and still warns', () => {
    const wrapper = render('FLAGGED', [])

    expect(wrapper.get('[role="status"]').text()).toBe(en.guardrail.flagged)
    expect(wrapper.find('.popover li').exists()).toBe(false)
  })
})
