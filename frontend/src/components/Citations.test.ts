import { mount, type VueWrapper } from '@vue/test-utils'
import { nextTick } from 'vue'
import { afterEach, describe, expect, it } from 'vitest'
import type { LeaseSource } from '../api/types'
import { i18n } from '../i18n'
import ar from '../i18n/ar'
import en from '../i18n/en'
import type { ChatMessage } from '../stores/chat'
import { arabicLateFee, lateFee, noticePart, preamble } from '../test/leaseFixtures'
import MessageBubble from './MessageBubble.vue'

const mounted: VueWrapper[] = []

function render(
  text: string,
  sources: LeaseSource[] = [lateFee, noticePart, preamble, arabicLateFee],
  { animated = false } = {},
) {
  const message: ChatMessage = { id: 1, role: 'assistant', text, tools: [], actions: [], sources, streaming: false }
  const wrapper = mount(MessageBubble, {
    props: { message },
    attachTo: document.body,
    // Transitions are stubbed unless a test is about what happens while the drawer animates.
    global: { plugins: [i18n], stubs: animated ? { transition: false } : {} },
  })
  mounted.push(wrapper)
  return wrapper
}

afterEach(() => {
  mounted.splice(0).forEach((wrapper) => wrapper.unmount())
  i18n.global.locale.value = 'en'
})

type Wrapper = ReturnType<typeof render>

/** Chips inside the answer text, not the Sources row. */
const inline = (wrapper: Wrapper) => wrapper.findAll('.markdown button.cite')
const row = (wrapper: Wrapper) => wrapper.find('.citations')
/** The drawer, or null while it is closed. It is attached to the body, outside the message. */
function drawer(): HTMLElement | null {
  const dialog = document.body.querySelector<HTMLElement>('[role="dialog"]')
  const overlay = dialog?.closest<HTMLElement>('.el-overlay')
  return dialog && overlay?.style.display !== 'none' ? dialog : null
}
const facts = (dialog: HTMLElement) =>
  Object.fromEntries(
    [...dialog.querySelectorAll('dt')].map((dt) => [dt.textContent?.trim(), dt.nextElementSibling?.textContent?.replace(/\s+/g, ' ').trim()]),
  )

describe('citation chips', () => {
  it('shows a marker that names a returned clause as a button inside the sentence', () => {
    const wrapper = render('The late fee is 3% of the rent [LSE-A-504#5] for each month.')

    const chips = inline(wrapper)
    expect(chips).toHaveLength(1)
    expect(chips[0].element.tagName).toBe('BUTTON')
    expect(chips[0].attributes('type')).toBe('button')
    expect(chips[0].element.closest('p')?.textContent).toBe('The late fee is 3% of the rent A-504§5 for each month.')
    expect(wrapper.find('.markdown').text()).not.toContain('LSE-A-504#5')
  })

  it('names the chip by the lease and the clause for a reader who cannot see it', async () => {
    const wrapper = render('Fee [LSE-A-504#5], notice [LSE-A-504#4a], parties [LSE-A-504#0].')

    expect(inline(wrapper).map((chip) => chip.attributes('aria-label'))).toEqual([
      'Lease A-504, clause 5',
      'Lease A-504, clause 4a',
      'Lease A-504, preamble',
    ])
    expect(inline(wrapper).every((chip) => chip.attributes('aria-haspopup') === 'dialog')).toBe(true)

    i18n.global.locale.value = 'ar'
    await nextTick()
    expect(inline(wrapper).map((chip) => chip.attributes('aria-label'))).toEqual([
      'العقد A-504، البند 5',
      'العقد A-504، البند 4a',
      'العقد A-504، مقدمة العقد',
    ])
  })

  it('leaves a marker that names no clause of this answer as plain text', () => {
    const wrapper = render('Pets are allowed [LSE-A-504#9] and [LSE-B-101#5].', [lateFee])

    expect(inline(wrapper)).toHaveLength(0)
    expect(row(wrapper).exists()).toBe(false)
    expect(wrapper.find('.markdown').text()).toBe('Pets are allowed [LSE-A-504#9] and [LSE-B-101#5].')
  })

  it('shows no chip at all for an answer without sources', () => {
    const wrapper = render('The fee is 3% [LSE-A-504#5].', [])

    expect(wrapper.findAll('button')).toHaveLength(0)
    expect(wrapper.find('.markdown').text()).toBe('The fee is 3% [LSE-A-504#5].')
  })

  it('shows markers that stand next to each other as two chips', () => {
    const wrapper = render('Both apply [LSE-A-504#5][LSE-A-504#4a].')

    expect(inline(wrapper).map((chip) => chip.text())).toEqual(['A-504§5', 'A-504§4a'])
  })

  it('shows chips in list items and in table cells', () => {
    const wrapper = render(
      '- Fee 3% [LSE-A-504#5]\n- Notice 60 days [LSE-A-504#4a]\n\n| Unit | Clause |\n|---|---|\n| A-504 | [LSE-A-504#5] |',
    )

    expect(wrapper.findAll('li button.cite')).toHaveLength(2)
    expect(wrapper.findAll('td button.cite')).toHaveLength(1)
    expect(wrapper.find('.table-wrap td button.cite').attributes('aria-label')).toBe('Lease A-504, clause 5')
  })

  it('shows a chip in Arabic text without taking the direction from its code', () => {
    const wrapper = render('[LSE-A-203#5] تستحق غرامة تأخير قدرها 3 بالمائة.')

    const chip = inline(wrapper)[0]
    expect(chip.text()).toBe('A-203§5')
    expect(chip.element.querySelector('bdi')?.textContent).toBe('A-203')
    expect(wrapper.find('.markdown').attributes('dir')).toBe('auto')
  })

  it('shows the preamble by its name instead of a clause number', () => {
    const wrapper = render('The parties are named [LSE-A-504#0].')

    expect(inline(wrapper)[0].text()).toBe(`A-504${en.lease.preamble}`)
  })

  it('can be reached and pressed with the keyboard', async () => {
    const wrapper = render('Fee [LSE-A-504#5].')
    const chip = inline(wrapper)[0].element as HTMLButtonElement

    chip.focus()

    expect(document.activeElement).toBe(chip)
    expect(chip.tabIndex).toBe(0)
    expect(chip.disabled).toBe(false)
  })
})

describe('citation chips and untrusted text', () => {
  it('keeps the sanitiser in force around a marker that breaks out of its brackets', () => {
    const wrapper = render(
      'Fee [LSE-A-504#5"><img src=x onerror=alert(1)>] and <img src=x onerror=alert(2)> <b onmouseover=alert(3)>x</b>',
    )

    expect(wrapper.html()).not.toMatch(/onerror|onmouseover|alert/)
    expect(inline(wrapper)).toHaveLength(0)
    expect(row(wrapper).exists()).toBe(false)
  })

  it('keeps the sanitiser in force in an answer that has chips', () => {
    const wrapper = render('<script>alert(1)</script><a href="javascript:alert(2)" onclick="alert(3)">x</a> fee [LSE-A-504#5]')

    expect(inline(wrapper)).toHaveLength(1)
    expect(wrapper.html()).not.toMatch(/<script|javascript:|onclick/)
  })

  it('keeps markup written inside an attribute from becoming elements with handlers', () => {
    const wrapper = render('<span title="<table><img src=x onerror=alert(1)>">fee</span> [LSE-A-504#5]')

    const everyElement = [...wrapper.element.querySelectorAll('*')]
    expect(everyElement.flatMap((element) => element.getAttributeNames()).filter((name) => name.startsWith('on'))).toEqual([])
    expect(wrapper.find('img').exists()).toBe(false)
    expect(wrapper.find('span[title]').attributes('title')).toBe('<table><img src=x onerror=alert(1)>')
    expect(inline(wrapper)).toHaveLength(1)
  })

  it('shows a source whose fields are markup as text, in the chip, the row and the drawer', async () => {
    const hostile: LeaseSource = {
      ...lateFee,
      unitCode: '<img src=x onerror=alert(1)>',
      tenantNameEn: '<script>alert(2)</script>',
      documentTitle: '<svg onload=alert(3)>',
      sectionTitle: '<b onmouseover=alert(4)>fee</b>',
      text: '5. <b>Late</b>\n<img src=x onerror=alert(5)> fee',
    }
    const wrapper = render('Fee [LSE-A-504#5].', [hostile])

    expect(inline(wrapper)[0].text()).toBe('<img src=x onerror=alert(1)>§5')
    await inline(wrapper)[0].trigger('click')

    expect(wrapper.find('img').exists()).toBe(false)
    const dialog = drawer() as HTMLElement
    const shown = [...dialog.querySelectorAll('h2, .el-drawer__body *')].map((element) => element.tagName.toLowerCase())
    expect(new Set(shown)).toEqual(new Set(['h2', 'div', 'dl', 'dt', 'dd', 'bdi', 'span', 'blockquote']))
    expect(dialog.querySelector('h2')?.textContent?.trim()).toBe('<svg onload=alert(3)>')
    expect(dialog.textContent).toContain('<script>alert(2)</script>')
    expect(dialog.textContent).toContain('<b onmouseover=alert(4)>fee</b>')
  })

  it('does not make a chip of a marker that is the text of a link, nor of one with a script link', () => {
    const wrapper = render('[[LSE-A-504#5]](javascript:alert(1)) and [[LSE-A-504#4a]](https://example.com)')

    expect(wrapper.find('.markdown a[href^="javascript"]').exists()).toBe(false)
    expect(inline(wrapper)).toHaveLength(0)
    expect(wrapper.find('.markdown a[href="https://example.com"]').text()).toBe('[LSE-A-504#4a]')
  })

  it('links to the PDF only when the server gave the path of a lease document', async () => {
    const wrapper = render('Fee [LSE-A-504#5].', [{ ...lateFee, pdfPath: 'javascript:alert(1)' }])

    await inline(wrapper)[0].trigger('click')

    expect((drawer() as HTMLElement).querySelector('a')).toBeNull()
  })
})

describe('the Sources row', () => {
  it('lists each cited clause once, in the order of the answer, and not the clauses that were not cited', () => {
    const wrapper = render('Notice [LSE-A-504#4a]. Fee [LSE-A-504#5]. Notice again [LSE-A-504#4a].')

    expect(row(wrapper).findAll('button.cite').map((chip) => chip.text())).toEqual(['A-504§4a', 'A-504§5'])
    expect(row(wrapper).text()).toContain(en.lease.sources.split(' | ')[1])
  })

  it('says Source for one clause and Sources for several, in both languages', async () => {
    const one = render('Fee [LSE-A-504#5].')
    const two = render('Fee [LSE-A-504#5], notice [LSE-A-504#4a].')

    expect(row(one).find('.label').text()).toBe('Source')
    expect(row(two).find('.label').text()).toBe('Sources')

    i18n.global.locale.value = 'ar'
    await nextTick()
    expect(row(one).find('.label').text()).toBe('المصدر')
    expect(row(two).find('.label').text()).toBe('المصادر')
  })

  it('is a group named by its label', () => {
    const wrapper = render('Fee [LSE-A-504#5].')

    const group = row(wrapper)
    expect(group.attributes('role')).toBe('group')
    expect(wrapper.get(`#${group.attributes('aria-labelledby')}`).text()).toBe('Source')
  })

  it('is not there for an answer that cites nothing', () => {
    expect(row(render('There are 6 units.')).exists()).toBe(false)
    expect(row(render('The fee is 3%.', [lateFee])).exists()).toBe(false)
  })

  it('opens the same clause as the chip in the text', async () => {
    const wrapper = render('Fee [LSE-A-504#5].')

    await row(wrapper).get('button.cite').trigger('click')

    expect(drawer()).not.toBeNull()
    expect(facts(drawer() as HTMLElement).Page).toBe('2')
  })
})

describe('the clause drawer', () => {
  it('shows where the clause is from and what it says', async () => {
    const wrapper = render('Fee [LSE-A-504#5].')

    expect(drawer()).toBeNull()
    await inline(wrapper)[0].trigger('click')

    const dialog = drawer() as HTMLElement
    expect(dialog.getAttribute('aria-modal')).toBe('true')
    expect(dialog.querySelector('h2')?.textContent?.trim()).toBe('Residential lease agreement, unit A-504')
    expect(facts(dialog)).toEqual({ Unit: 'A-504', Tenant: 'Khalid Al-Qahtani', Clause: '5 · Late payment', Page: '2' })
    const text = dialog.querySelector('blockquote') as HTMLElement
    expect(text.textContent?.trim()).toBe(
      'Rent not received within 5 days of its due date carries a late fee of 3% of the monthly rent, SAR 150.00 for each month.',
    )
    expect(text.textContent).not.toContain('5. Late payment')
    expect(text.getAttribute('dir')).toBe('ltr')
    expect(text.getAttribute('lang')).toBe('en')
  })

  it('is named by the title of the document', async () => {
    const wrapper = render('Fee [LSE-A-504#5].')

    await inline(wrapper)[0].trigger('click')

    const dialog = drawer() as HTMLElement
    expect(dialog.getAttribute('aria-labelledby')).toBe(dialog.querySelector('h2')?.id)
  })

  it('keeps codes and amounts in their own order inside the text', async () => {
    const wrapper = render('Fee [LSE-A-504#5].')

    await inline(wrapper)[0].trigger('click')

    const isolated = [...(drawer() as HTMLElement).querySelectorAll('blockquote bdi')].map((bdi) => bdi.textContent)
    expect(isolated).toEqual(['5', '3', '150.00'])
  })

  it('opens the PDF of the document at the page of the clause in a new tab, without handing over the opener', async () => {
    const wrapper = render('Fee [LSE-A-504#5]. Notice [LSE-A-504#4a].')

    await inline(wrapper)[0].trigger('click')

    const link = (drawer() as HTMLElement).querySelector('a') as HTMLAnchorElement
    expect(link.textContent?.trim()).toBe(en.lease.openPdf)
    expect(link.getAttribute('href')).toBe('/api/lease-documents/LSE-A-504/pdf#page=2')
    expect(link.getAttribute('target')).toBe('_blank')
    expect(link.getAttribute('rel')).toContain('noopener')
  })

  it('shows the preamble, which has no number or title, and a part of a split clause by its letter', async () => {
    const wrapper = render('Parties [LSE-A-504#0]. Notice [LSE-A-504#4a].')
    const [first, second] = inline(wrapper)

    await first.trigger('click')
    expect(facts(drawer() as HTMLElement).Clause).toBe(en.lease.preamble)
    expect((drawer() as HTMLElement).querySelector('blockquote')?.textContent?.trim()).toBe(preamble.text)

    await second.trigger('click')
    expect(facts(drawer() as HTMLElement).Clause).toBe('4a · Notice period')
    expect(facts(drawer() as HTMLElement).Page).toBe('1')
  })

  it('closes with Escape and gives the focus back to the chip that opened it', async () => {
    const wrapper = render('Fee [LSE-A-504#5]. Notice [LSE-A-504#4a].')
    const chip = inline(wrapper)[1].element as HTMLButtonElement
    chip.focus()
    await inline(wrapper)[1].trigger('click')
    expect(drawer()).not.toBeNull()
    expect(drawer()?.contains(document.activeElement)).toBe(true)

    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', code: 'Escape', bubbles: true }))
    await nextTick()
    await nextTick()

    expect(drawer()).toBeNull()
    expect(document.activeElement).toBe(chip)
  })

  it('gives the focus back to the chip even when the click did not focus it, as in Safari', async () => {
    const wrapper = render('Fee [LSE-A-504#5].')
    ;(document.activeElement as HTMLElement | null)?.blur()
    await inline(wrapper)[0].trigger('click')

    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', code: 'Escape', bubbles: true }))
    await nextTick()
    await nextTick()

    expect(drawer()).toBeNull()
    expect(document.activeElement).toBe(inline(wrapper)[0].element)
  })

  it('gives the focus back to the chip of the Sources row when that opened it', async () => {
    const wrapper = render('Fee [LSE-A-504#5].')
    const chip = row(wrapper).get('button.cite').element as HTMLButtonElement
    chip.focus()
    await row(wrapper).get('button.cite').trigger('click')

    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', code: 'Escape', bubbles: true }))
    await nextTick()
    await nextTick()

    expect(drawer()).toBeNull()
    expect(document.activeElement).toBe(chip)
  })

  it('closes with its close button', async () => {
    const wrapper = render('Fee [LSE-A-504#5].')
    await inline(wrapper)[0].trigger('click')

    ;(drawer()?.querySelector('.el-drawer__close-btn') as HTMLButtonElement).click()
    await nextTick()
    await nextTick()

    expect(drawer()).toBeNull()
  })

  it('can be opened again after it was closed', async () => {
    const wrapper = render('Fee [LSE-A-504#5].')

    for (let times = 0; times < 2; times++) {
      await inline(wrapper)[0].trigger('click')
      expect(drawer()).not.toBeNull()
      document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', code: 'Escape', bubbles: true }))
      await nextTick()
      await nextTick()
      expect(drawer()).toBeNull()
    }
  })

  it('stays open, and does not open again by itself, when a later model round empties the answer it was opened from', async () => {
    const wrapper = render('The fee is 3% [LSE-A-504#5]. Let me check A-203.', [lateFee])
    const message = wrapper.props('message') as ChatMessage
    await inline(wrapper)[0].trigger('click')
    expect(drawer()).not.toBeNull()

    await wrapper.setProps({ message: { ...message, text: '', streaming: true } })

    expect(inline(wrapper)).toHaveLength(0)
    expect(drawer()).not.toBeNull()
    expect(facts(drawer() as HTMLElement).Clause).toBe('5 · Late payment')

    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', code: 'Escape', bubbles: true }))
    await nextTick()
    await nextTick()
    expect(drawer()).toBeNull()

    await wrapper.setProps({ message: { ...message, text: 'Both leases charge 3% [LSE-A-504#5].' } })

    expect(inline(wrapper)).toHaveLength(1)
    expect(drawer()).toBeNull()
  })

  it('is not mounted for an answer that cites nothing', () => {
    render('There are 6 units.')

    expect(document.body.querySelector('[role="dialog"]')).toBeNull()
  })
})

describe('the clause drawer in Arabic', () => {
  it('shows the tenant by the Arabic name and the labels in Arabic', async () => {
    const wrapper = render('Fee [LSE-A-504#5].')
    i18n.global.locale.value = 'ar'
    await nextTick()

    await inline(wrapper)[0].trigger('click')

    expect(facts(drawer() as HTMLElement)).toEqual({
      [ar.lease.unit]: 'A-504',
      [ar.lease.tenant]: 'خالد القحطاني',
      [ar.lease.clause]: '5 · Late payment',
      [ar.lease.page]: '2',
    })
    expect(drawer()?.querySelector('a')?.textContent?.trim()).toBe(ar.lease.openPdf)
  })

  it('reads an Arabic lease right to left whatever the language of the app', async () => {
    const wrapper = render('Fee [LSE-A-203#5].')

    await inline(wrapper)[0].trigger('click')

    const dialog = drawer() as HTMLElement
    const text = dialog.querySelector('blockquote') as HTMLElement
    expect(text.getAttribute('dir')).toBe('rtl')
    expect(text.getAttribute('lang')).toBe('ar')
    expect(text.textContent).not.toContain('البند 5: غرامة التأخير')
    expect(text.textContent?.trim()).toBe(
      'إذا لم يصل الإيجار خلال 5 أيام من تاريخ استحقاقه تستحق غرامة قدرها 3 بالمائة، أي 144 ريال سعودي.',
    )
    expect(dialog.querySelector('h2')?.getAttribute('dir')).toBe('rtl')
    expect(facts(dialog).Tenant).toBe('Ahmed Al-Otaibi')
    expect(dialog.querySelector('dd span[dir]')?.getAttribute('dir')).toBe('rtl')
    expect(dialog.querySelector('a')?.getAttribute('href')).toBe('/api/lease-documents/LSE-A-203/pdf#page=3')
  })

  it('comes in from the left edge in Arabic and from the right edge in English', async () => {
    const wrapper = render('Fee [LSE-A-504#5].')
    await inline(wrapper)[0].trigger('click')
    expect(drawer()?.classList.contains('rtl')).toBe(true)

    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', code: 'Escape', bubbles: true }))
    await nextTick()
    await nextTick()
    i18n.global.locale.value = 'ar'
    await nextTick()
    await inline(wrapper)[0].trigger('click')

    expect(drawer()?.classList.contains('ltr')).toBe(true)
  })
})

describe('the clause drawer while it animates', () => {
  const frame = () => new Promise((resolve) => setTimeout(resolve, 30))

  it('opens again at once when its chip is clicked as it is closing, and gives the focus back after it has closed', async () => {
    const wrapper = render('Fee [LSE-A-504#5].', undefined, { animated: true })
    const chip = inline(wrapper)[0]
    ;(chip.element as HTMLElement).focus()
    await chip.trigger('click')
    await frame()
    expect(drawer()).not.toBeNull()

    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', code: 'Escape', bubbles: true }))
    await nextTick()
    await chip.trigger('click')
    await frame()

    expect(drawer()).not.toBeNull()

    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', code: 'Escape', bubbles: true }))
    await frame()
    await frame()

    expect(drawer()).toBeNull()
    expect(document.activeElement).toBe(chip.element)
  })
})

describe('a streaming answer', () => {
  it('shows the chip once its marker is complete and keeps the same button while more text arrives', async () => {
    const wrapper = render('Fee [LSE-A-504#', [lateFee])
    const message = wrapper.props('message') as ChatMessage
    expect(inline(wrapper)).toHaveLength(0)

    await wrapper.setProps({ message: { ...message, text: 'Fee [LSE-A-504#5] is' } })
    const chip = inline(wrapper)[0].element
    await wrapper.setProps({ message: { ...message, text: 'Fee [LSE-A-504#5] is 3% of the rent' } })

    expect(inline(wrapper)[0].element).toBe(chip)
  })
})
