import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { nextTick } from 'vue'
import { ApiError } from '../api/http'
import type { ActionView, ChatEvent } from '../api/types'
import { i18n } from '../i18n'
import ar from '../i18n/ar'
import en from '../i18n/en'
import { useChatStore } from '../stores/chat'
import {
  maintenanceAction,
  paymentAction,
  paymentPreviewView,
  reminderAction,
} from '../test/actionFixtures'
import MessageBubble from './MessageBubble.vue'

const approveAction = vi.fn()
const rejectAction = vi.fn()
const previewAction = vi.fn()
const fetchAction = vi.fn()
vi.mock('../api/actions', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../api/actions')>()),
  approveAction: (...args: unknown[]) => approveAction(...args),
  rejectAction: (...args: unknown[]) => rejectAction(...args),
  previewAction: (...args: unknown[]) => previewAction(...args),
  fetchAction: (...args: unknown[]) => fetchAction(...args),
}))

const streamChat = vi.fn()
const streamConfirmation = vi.fn()
vi.mock('../api/chat', () => ({
  streamChat: (...args: unknown[]) => streamChat(...args),
  streamConfirmation: (...args: unknown[]) => streamConfirmation(...args),
}))
vi.mock('../composables/useAppInfo', () => ({ useAppInfo: () => ({ info: { value: null } }) }))

const done: ChatEvent = {
  type: 'done', runId: 'r1', status: 'OK', provider: 'offline', model: 'rule-based',
  inputTokens: 10, outputTokens: 5, toolCalls: 1, iterations: 1, latencyMs: 30, costUsd: 0, truncated: false,
}

/** Puts the action into an assistant message the way the stream does, and shows that message. */
async function show(action: ActionView, options: { attach?: boolean } = {}) {
  const chat = useChatStore()
  streamChat.mockImplementationOnce(async function* () {
    yield { type: 'action_proposed', ...action } as ChatEvent
    yield done
  })
  await chat.send('please do it')
  const wrapper = mount(MessageBubble, {
    props: { message: chat.messages[1] },
    global: { plugins: [i18n] },
    attachTo: options.attach ? document.body : undefined,
  })
  return { chat, wrapper }
}

const buttonLabelled = (wrapper: VueWrapper, label: string) =>
  wrapper.findAll('button').find((button) => button.text() === label)

async function press(wrapper: VueWrapper, label: string) {
  const button = buttonLabelled(wrapper, label)
  if (!button) throw new Error(`no button "${label}" in: ${wrapper.text()}`)
  await button.trigger('click')
  await flushPromises()
}

async function typeAmount(wrapper: VueWrapper, value: string) {
  await wrapper.get('input[type="text"]').setValue(value)
}

/** Asks the editor for a preview, as Enter or the "Update preview" button does. */
async function updatePreview(wrapper: VueWrapper) {
  await wrapper.get('form').trigger('submit')
  await flushPromises()
}

const approved = (action: ActionView, result: unknown, actor = 'visitor-1a2b3c4d') =>
  ({ ...action, status: 'APPROVED', result, actor, decidedAt: '2026-10-10T15:01:00Z' }) as ActionView

const paymentResult = { unitCode: 'B-203', amount: 5000, applied: 5000, overpaymentCredit: 0, outstandingAfter: 9400 }

beforeEach(() => {
  setActivePinia(createPinia())
  for (const mock of [approveAction, rejectAction, previewAction, fetchAction, streamChat, streamConfirmation]) {
    mock.mockReset()
  }
  streamConfirmation.mockImplementation(async function* () {
    yield { type: 'token', text: 'Confirmed.' } as ChatEvent
    yield done
  })
})

afterEach(() => {
  i18n.global.locale.value = 'en'
  vi.useRealTimers()
})

describe('ApprovalCard waiting for a decision', () => {
  it('shows what will change: the summary, the facts, the months and the balance before and after', async () => {
    const { wrapper } = await show(paymentAction())

    const card = wrapper.get('[role="group"]')
    expect(card.text()).toContain(en.actions.kinds.RECORD_PAYMENT)
    expect(card.text()).toContain(en.actions.status.PROPOSED)
    expect(card.text()).toContain('Record a SAR 5,000.00 bank transfer payment from Ahmed Al-Harbi')
    expect(card.text()).toContain('Ahmed Al-Harbi')
    expect(card.text()).toContain('B-203')
    expect(card.text()).toContain(en.actions.payment.methods.BANK_TRANSFER)
    const rows = card.findAll('tbody tr').map((row) => row.findAll('th, td').map((cell) => cell.text()))
    expect(rows).toEqual([
      ['Jul 2026', '4,800.00', '0.00', '4,800.00'],
      ['Aug 2026', '4,800.00', '0.00', '200.00'],
    ])
    expect(card.get('.outstanding').text()).toContain('SAR 14,400.00')
    expect(card.get('.outstanding').text()).toContain('SAR 9,400.00')
  })

  it('is a labelled group and announces nothing until its state changes', async () => {
    const { wrapper } = await show(paymentAction())

    const card = wrapper.get('[role="group"]')
    const heading = card.get('h3')
    expect(card.attributes('aria-labelledby')).toBe(heading.attributes('id'))
    expect(heading.text()).toBe(en.actions.kinds.RECORD_PAYMENT)
    expect(card.get('[role="status"]').text()).toBe('')
    expect(card.attributes('aria-busy')).toBe('false')
  })

  it('shows the summary in the language of the page', async () => {
    const { wrapper } = await show(paymentAction())

    i18n.global.locale.value = 'ar'
    await nextTick()

    const card = wrapper.get('[role="group"]')
    expect(card.get('.summary').text()).toContain('تسجيل دفعة بقيمة 5,000.00 ريال سعودي')
    expect(card.get('h3').text()).toBe(ar.actions.kinds.RECORD_PAYMENT)
    expect(card.text()).toContain('أحمد الحربي')
    expect(card.text()).toContain(ar.actions.payment.methods.BANK_TRANSFER)
  })

  it('shows a maintenance request with its unit, category, priority and description', async () => {
    const { wrapper } = await show(maintenanceAction())

    const facts = wrapper.get('.facts').text()
    expect(facts).toContain('A-301')
    expect(facts).toContain(en.actions.maintenance.categories.PLUMBING)
    expect(facts).toContain(en.actions.maintenance.priorities.HIGH)
    expect(facts).toContain('Kitchen sink leaks')
    expect(buttonLabelled(wrapper, en.actions.editAmount)).toBeUndefined()
  })

  it('shows a reminder in its own language and says it is stored in the outbox, not sent', async () => {
    const { wrapper } = await show(reminderAction())

    const card = wrapper.get('[role="group"]')
    expect(card.text()).toContain('تذكير بسداد الإيجار')
    expect(card.text()).toContain(en.actions.reminder.outbox)
    const body = card.get('.body')
    expect(body.attributes('dir')).toBe('rtl')
    expect(body.attributes('lang')).toBe('ar')
  })

  it('shows text a person typed as text, never as markup', async () => {
    const description = '<img src=x onerror="alert(1)"> Kitchen <b>sink</b> leaks'
    const { wrapper } = await show(
      maintenanceAction({ preview: { ...maintenanceAction().preview, description }, summaryEn: description }),
    )

    const card = wrapper.get('[role="group"]')
    expect(card.find('img').exists()).toBe(false)
    expect(card.find('b').exists()).toBe(false)
    expect(card.text()).toContain(description)
  })

  describe('expiry', () => {
    beforeEach(() => {
      vi.useFakeTimers()
      vi.setSystemTime(new Date('2026-10-10T15:00:00Z'))
    })

    const closing = (overrides = {}) => paymentAction({ expiresAt: '2026-10-10T15:15:00Z', ...overrides })

    it('counts down to the expiry time', async () => {
      const { wrapper } = await show(closing())
      const timer = () => wrapper.get('[role="timer"]').text()

      expect(timer()).toContain('15:00')

      await vi.advanceTimersByTimeAsync(61_000)

      expect(timer()).toContain('13:59')
    })

    it('asks the server what became of the proposal when the time is up, and shows it expired', async () => {
      const action = closing()
      const { wrapper } = await show(action)
      fetchAction.mockResolvedValue({ ...action, status: 'EXPIRED', decidedAt: '2026-10-10T15:15:00Z' })

      await vi.advanceTimersByTimeAsync(15 * 60_000 + 1000)

      expect(fetchAction).toHaveBeenCalledTimes(1)
      expect(fetchAction).toHaveBeenCalledWith(action.id)
      const card = wrapper.get('[role="group"]')
      expect(card.text()).toContain(en.actions.status.EXPIRED)
      expect(card.text()).toContain(en.actions.outcome.expired)
      expect(card.find('.buttons').exists()).toBe(false)
      expect(card.get('[role="status"]').text()).toBe(en.actions.announce.EXPIRED)
      expect(streamConfirmation).not.toHaveBeenCalled()
    })
  })
})

describe('ApprovalCard approving', () => {
  it('approves without edits, shows the result in green and has the assistant confirm it once', async () => {
    const action = paymentAction()
    const { wrapper } = await show(action)
    approveAction.mockResolvedValue({ action: approved(action, paymentResult), replayed: false })

    await press(wrapper, en.actions.approve)

    expect(approveAction).toHaveBeenCalledTimes(1)
    const [id, key, edit] = approveAction.mock.calls[0]
    expect(id).toBe(action.id)
    expect(key).toMatch(/^[0-9a-f-]{36}$/)
    expect(edit).toBeUndefined()
    const card = wrapper.get('[role="group"]')
    expect(card.classes()).toContain('approved')
    expect(card.text()).toContain(en.actions.status.APPROVED)
    expect(card.get('.outcome').text()).toContain('Recorded SAR 5,000.00')
    expect(card.get('.outcome').text()).toContain('SAR 9,400.00 is still outstanding')
    expect(card.get('[role="status"]').text()).toBe(en.actions.announce.APPROVED)
    expect(card.find('.buttons').exists()).toBe(false)
    expect(streamConfirmation).toHaveBeenCalledTimes(1)
    expect(streamConfirmation.mock.calls[0][0]).toBe(action.id)
  })

  it('shows no proposal-time diff on an approved payment: its outcome is what approval made of the invoices', async () => {
    const action = paymentAction()
    const { wrapper } = await show(action)
    // Another payment was recorded after this one was proposed, so approval met a balance of 3,000, not 14,400.
    approveAction.mockResolvedValue({
      action: approved(action, { ...paymentResult, amount: 1000, applied: 1000, outstandingAfter: 2000 }),
      replayed: false,
    })

    await press(wrapper, en.actions.approve)

    const card = wrapper.get('[role="group"]')
    expect(card.find('tbody').exists()).toBe(false)
    expect(card.find('.outstanding').exists()).toBe(false)
    expect(card.text()).not.toContain('14,400')
    expect(card.get('.outcome').text()).toContain('Recorded SAR 1,000.00')
    expect(card.get('.outcome').text()).toContain('SAR 2,000.00 is still outstanding')
  })

  it('says how much of an approved overpayment was not applied to any invoice', async () => {
    const action = paymentAction()
    const { wrapper } = await show(action)
    approveAction.mockResolvedValue({
      action: approved(action, { ...paymentResult, amount: 20000, applied: 14400, overpaymentCredit: 5600, outstandingAfter: 0 }),
      replayed: false,
    })

    await press(wrapper, en.actions.approve)

    const outcome = wrapper.get('.outcome').text()
    expect(outcome).toContain('Recorded SAR 14,400.00')
    expect(outcome).toContain('SAR 5,600.00 was more than was owed')

    i18n.global.locale.value = 'ar'
    await nextTick()
    expect(wrapper.get('.outcome').text()).toContain('5,600.00')
    expect(wrapper.get('.outcome').text()).toContain('لم يُطبَّق')
  })

  it('names the request an approved maintenance request opened, in both languages', async () => {
    const action = maintenanceAction()
    const { wrapper } = await show(action)
    approveAction.mockResolvedValue({
      action: approved(action, { requestId: 4711, unitCode: 'A-301', status: 'OPEN' }),
      replayed: false,
    })

    await press(wrapper, en.actions.approve)

    const card = wrapper.get('[role="group"]')
    expect(card.classes()).toContain('approved')
    expect(card.get('.outcome').text()).toBe('Maintenance request #4711 was opened.')
    expect(card.get('.outcome bdi').text()).toBe('4711')
    expect(card.get('.facts').text()).toContain('Kitchen sink leaks')
    expect(streamConfirmation).toHaveBeenCalledTimes(1)

    i18n.global.locale.value = 'ar'
    await nextTick()
    expect(card.get('.outcome').text()).toBe('فُتح طلب الصيانة رقم 4711.')
    expect(card.get('.outcome bdi').text()).toBe('4711')
  })

  it('shows the message an approved reminder stored, which is written again at approval, not the proposed one', async () => {
    const action = reminderAction()
    const { wrapper } = await show(action)
    const stored = {
      messageId: '9f0c2f3e-0000-4000-8000-000000000001',
      unitCode: 'B-203',
      tenantEn: 'Ahmed Al-Harbi',
      tenantAr: 'أحمد الحربي',
      purpose: 'RENT_REMINDER',
      language: 'en',
      subject: 'Rent reminder for B-203',
      body: 'Dear Ahmed, SAR 1,000.00 of your rent is overdue.',
      delivered: false,
    }
    approveAction.mockResolvedValue({ action: approved(action, stored), replayed: false })

    await press(wrapper, en.actions.approve)

    const card = wrapper.get('[role="group"]')
    expect(card.classes()).toContain('approved')
    expect(card.text()).toContain('Rent reminder for B-203')
    expect(card.text()).toContain('SAR 1,000.00 of your rent is overdue.')
    expect(card.text()).not.toContain('عزيزي أحمد')
    expect(card.get('.body').attributes('dir')).toBe('ltr')
    expect(card.get('.body').attributes('lang')).toBe('en')
    expect(card.get('.facts').text()).toContain(en.actions.reminder.languages.EN)
    expect(card.get('.outcome').text()).toBe(en.actions.outcome.reminder)

    i18n.global.locale.value = 'ar'
    await nextTick()
    expect(card.get('.outcome').text()).toBe(ar.actions.outcome.reminder)
    expect(card.get('.facts').text()).toContain(ar.actions.reminder.languages.EN)
  })

  it('locks the card while the approval is on its way', async () => {
    const action = paymentAction()
    const { wrapper } = await show(action)
    let resolve: (value: unknown) => void = () => {}
    approveAction.mockReturnValue(new Promise((r) => (resolve = r)))

    await buttonLabelled(wrapper, en.actions.approve)?.trigger('click')

    const card = wrapper.get('[role="group"]')
    expect(card.attributes('aria-busy')).toBe('true')
    expect(card.text()).toContain(en.actions.status.APPROVING)
    expect(card.get('[role="status"]').text()).toBe(en.actions.announce.APPROVING)
    for (const button of card.findAll('.buttons button')) {
      expect(button.attributes('disabled')).toBeDefined()
    }

    resolve({ action: approved(action, paymentResult), replayed: false })
    await flushPromises()
    expect(card.attributes('aria-busy')).toBe('false')
  })

  it('sends one request for a double click', async () => {
    const action = paymentAction()
    const { wrapper } = await show(action)
    let resolve: (value: unknown) => void = () => {}
    approveAction.mockReturnValue(new Promise((r) => (resolve = r)))
    const approve = buttonLabelled(wrapper, en.actions.approve)

    // Both clicks land before Vue has had the chance to disable the button.
    void approve?.trigger('click')
    await approve?.trigger('click')
    resolve({ action: approved(action, paymentResult), replayed: false })
    await flushPromises()

    expect(approveAction).toHaveBeenCalledTimes(1)
  })

  it('retries with the same idempotency key after a failure, so the change cannot be made twice', async () => {
    const action = paymentAction()
    const { wrapper } = await show(action)
    approveAction.mockRejectedValueOnce(new TypeError('Failed to fetch'))
    approveAction.mockResolvedValueOnce({ action: approved(action, paymentResult), replayed: true })

    await press(wrapper, en.actions.approve)

    expect(wrapper.get('[role="alert"]').text()).toBe(en.actions.errors.network)
    expect(buttonLabelled(wrapper, en.actions.approve)?.attributes('disabled')).toBeUndefined()

    await press(wrapper, en.actions.approve)

    expect(approveAction).toHaveBeenCalledTimes(2)
    expect(approveAction.mock.calls[1][1]).toBe(approveAction.mock.calls[0][1])
    expect(wrapper.get('[role="group"]').classes()).toContain('approved')
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
  })

  it('uses a different key for every proposal', async () => {
    const first = paymentAction()
    const second = maintenanceAction()
    const chat = useChatStore()
    streamChat.mockImplementationOnce(async function* () {
      yield { type: 'action_proposed', ...first } as ChatEvent
      yield { type: 'action_proposed', ...second } as ChatEvent
      yield done
    })
    await chat.send('both')
    const wrapper = mount(MessageBubble, { props: { message: chat.messages[1] }, global: { plugins: [i18n] } })
    approveAction.mockRejectedValue(new TypeError('Failed to fetch'))

    for (const button of wrapper.findAll('button').filter((b) => b.text() === en.actions.approve)) {
      await button.trigger('click')
      await flushPromises()
    }

    expect(approveAction.mock.calls[0][1]).not.toBe(approveAction.mock.calls[1][1])
  })
})

describe('ApprovalCard shown again', () => {
  const mountAgain = (chat: ReturnType<typeof useChatStore>) =>
    mount(MessageBubble, { props: { message: chat.messages[1] }, global: { plugins: [i18n] } })

  it('is still locked while its approval is on its way, so it cannot be decided twice or against itself', async () => {
    const action = paymentAction()
    const { chat, wrapper } = await show(action)
    let resolve: (value: unknown) => void = () => {}
    approveAction.mockReturnValue(new Promise((r) => (resolve = r)))
    await buttonLabelled(wrapper, en.actions.approve)?.trigger('click')
    // The chat is left and entered again, which mounts the card anew.
    wrapper.unmount()

    const again = mountAgain(chat)

    const card = again.get('[role="group"]')
    expect(card.attributes('aria-busy')).toBe('true')
    expect(card.text()).toContain(en.actions.status.APPROVING)
    for (const button of card.findAll('.buttons button')) {
      expect(button.attributes('disabled')).toBeDefined()
    }
    await buttonLabelled(again, en.actions.approve)?.trigger('click')
    expect(approveAction).toHaveBeenCalledTimes(1)

    resolve({ action: approved(action, paymentResult), replayed: false })
    await flushPromises()

    expect(again.get('[role="group"]').classes()).toContain('approved')
    expect(again.get('[role="group"]').attributes('aria-busy')).toBe('false')
  })

  it('retries with the key of the first attempt, so the change cannot be made twice', async () => {
    const action = paymentAction()
    const { chat, wrapper } = await show(action)
    approveAction.mockRejectedValueOnce(new TypeError('Failed to fetch'))
    approveAction.mockResolvedValueOnce({ action: approved(action, paymentResult), replayed: true })
    await press(wrapper, en.actions.approve)
    wrapper.unmount()

    const again = mountAgain(chat)
    await press(again, en.actions.approve)

    expect(approveAction).toHaveBeenCalledTimes(2)
    expect(approveAction.mock.calls[1][1]).toBe(approveAction.mock.calls[0][1])
    expect(again.get('[role="group"]').classes()).toContain('approved')
  })
})

describe('ApprovalCard when the proposal is settled while it is being edited', () => {
  async function editedTo1000(action: ActionView) {
    const shown = await show(action)
    await press(shown.wrapper, en.actions.editAmount)
    previewAction.mockResolvedValue(paymentPreviewView(1000))
    await typeAmount(shown.wrapper, '1000')
    await updatePreview(shown.wrapper)
    expect(shown.wrapper.get('.summary').text()).toContain('SAR 1000.00')
    return shown
  }

  it('shows what the server holds after a refusal (409), not the edit that was never sent', async () => {
    const action = paymentAction()
    const { wrapper } = await editedTo1000(action)
    // Somebody else approved the proposal as it was.
    approveAction.mockRejectedValue(new ApiError(409, 'Conflict', 'action_already_decided'))
    fetchAction.mockResolvedValue(approved(action, paymentResult, 'visitor-99999999'))

    await press(wrapper, en.actions.approve)

    const card = wrapper.get('[role="group"]')
    expect(card.classes()).toContain('approved')
    expect(card.get('.summary').text()).toBe(action.summaryEn)
    expect(card.get('.summary').text()).not.toContain('1000.00')
    expect(card.find('.outstanding').exists()).toBe(false)
    expect(card.find('form').exists()).toBe(false)
    expect(card.get('.outcome').text()).toContain('Recorded SAR 5,000.00')
  })

  it('shows the proposal again when it is gone (404)', async () => {
    const action = paymentAction()
    const { wrapper } = await editedTo1000(action)
    approveAction.mockRejectedValue(new ApiError(404, 'Not Found', 'action_not_found'))

    await press(wrapper, en.actions.approve)

    const card = wrapper.get('[role="group"]')
    expect(card.get('.summary').text()).toBe(action.summaryEn)
    expect(card.find('form').exists()).toBe(false)
    expect(card.find('.buttons').exists()).toBe(false)
  })

  it('shows the proposal as expired when the time is up with the editor open', async () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-10-10T15:00:00Z'))
    const action = paymentAction({ expiresAt: '2026-10-10T15:15:00Z' })
    const { wrapper } = await show(action)
    previewAction.mockResolvedValue(paymentPreviewView(1000))
    await buttonLabelled(wrapper, en.actions.editAmount)?.trigger('click')
    await typeAmount(wrapper, '1000')
    await wrapper.get('form').trigger('submit')
    await vi.advanceTimersByTimeAsync(0)
    expect(wrapper.get('.summary').text()).toContain('SAR 1000.00')
    fetchAction.mockResolvedValue({ ...action, status: 'EXPIRED', decidedAt: '2026-10-10T15:15:00Z' })

    await vi.advanceTimersByTimeAsync(15 * 60_000 + 1000)

    const card = wrapper.get('[role="group"]')
    expect(card.text()).toContain(en.actions.status.EXPIRED)
    expect(card.get('.summary').text()).toBe(action.summaryEn)
    expect(card.find('form').exists()).toBe(false)
  })
})

describe('ApprovalCard rejecting', () => {
  it('asks for an optional reason, rejects with it and has the assistant confirm', async () => {
    const action = paymentAction()
    const { wrapper } = await show(action)
    rejectAction.mockResolvedValue({
      ...action, status: 'REJECTED', actor: 'visitor-1a2b3c4d', decisionReason: 'Wrong <b>tenant</b>',
    })

    await press(wrapper, en.actions.reject)
    expect(wrapper.text()).toContain(en.actions.reasonLabel)
    await wrapper.get('textarea').setValue('  Wrong <b>tenant</b>  ')
    await press(wrapper, en.actions.confirmReject)

    expect(rejectAction).toHaveBeenCalledWith(action.id, 'Wrong <b>tenant</b>')
    const card = wrapper.get('[role="group"]')
    expect(card.classes()).toContain('rejected')
    expect(card.get('.outcome').text()).toContain(en.actions.outcome.rejected)
    expect(card.get('.outcome').text()).toContain('Wrong <b>tenant</b>')
    expect(card.find('.outcome b').exists()).toBe(false)
    expect(card.get('[role="status"]').text()).toBe(en.actions.announce.REJECTED)
    expect(streamConfirmation).toHaveBeenCalledTimes(1)
  })

  it('rejects without a reason when none is given', async () => {
    const action = paymentAction()
    const { wrapper } = await show(action)
    rejectAction.mockResolvedValue({ ...action, status: 'REJECTED', actor: 'visitor-1a2b3c4d' })

    await press(wrapper, en.actions.reject)
    await press(wrapper, en.actions.confirmReject)

    expect(rejectAction).toHaveBeenCalledWith(action.id, undefined)
  })

  it('takes keyboard focus into the reason field when the form opens', async () => {
    const { wrapper } = await show(paymentAction(), { attach: true })

    await press(wrapper, en.actions.reject)

    expect(document.activeElement).toBe(wrapper.get('textarea').element)
    wrapper.unmount()
  })

  it('can be cancelled without deciding anything', async () => {
    const { wrapper } = await show(paymentAction())

    await press(wrapper, en.actions.reject)
    await press(wrapper, en.actions.cancel)

    expect(rejectAction).not.toHaveBeenCalled()
    expect(wrapper.find('textarea').exists()).toBe(false)
    expect(buttonLabelled(wrapper, en.actions.approve)).toBeDefined()
  })
})

describe('ApprovalCard editing the amount', () => {
  async function openEditor() {
    const action = paymentAction()
    const shown = await show(action)
    await press(shown.wrapper, en.actions.editAmount)
    return { ...shown, action }
  }

  it('previews the new amount without changing anything, shows the new diff and approves with it', async () => {
    const { wrapper, action } = await openEditor()
    previewAction.mockResolvedValue(paymentPreviewView(1000))
    approveAction.mockResolvedValue({
      action: approved(action, { ...paymentResult, amount: 1000, applied: 1000, outstandingAfter: 13400 }),
      replayed: false,
    })

    expect(wrapper.get('input[type="text"]').element).toHaveProperty('value', '5000')
    await typeAmount(wrapper, '1000')
    expect(buttonLabelled(wrapper, en.actions.approve)?.attributes('disabled')).toBeDefined()
    await updatePreview(wrapper)

    expect(previewAction).toHaveBeenCalledWith(action.id, { amount: 1000, overridePayment: false })
    expect(approveAction).not.toHaveBeenCalled()
    expect(wrapper.get('.summary').text()).toContain('SAR 1000.00')
    expect(wrapper.get('.outstanding').text()).toContain('SAR 13,400.00')
    expect(wrapper.findAll('tbody tr')).toHaveLength(1)

    await press(wrapper, en.actions.approve)

    expect(approveAction).toHaveBeenCalledWith(action.id, expect.any(String), { amount: 1000, overridePayment: false })
  })

  it('takes keyboard focus into the amount field when the editor opens', async () => {
    const { wrapper } = await show(paymentAction(), { attach: true })

    await press(wrapper, en.actions.editAmount)

    expect(document.activeElement).toBe(wrapper.get('input[type="text"]').element)
    wrapper.unmount()
  })

  it('previews on Enter and accepts decimals and Arabic-Indic digits', async () => {
    const { wrapper, action } = await openEditor()
    previewAction.mockResolvedValue(paymentPreviewView(1250.5))

    await typeAmount(wrapper, '١٢٥٠٫٥')
    await updatePreview(wrapper)

    expect(previewAction).toHaveBeenCalledWith(action.id, { amount: 1250.5, overridePayment: false })
  })

  it.each(['', 'abc', '0', '-5', '12.345', '1,000', '10000000.01'])(
    'refuses %j locally and sends nothing',
    async (text) => {
      const { wrapper } = await openEditor()

      await typeAmount(wrapper, text)
      await updatePreview(wrapper)

      expect(previewAction).not.toHaveBeenCalled()
      expect(wrapper.get('[role="alert"]').text()).toBe(en.actions.editor.invalid)
      expect(buttonLabelled(wrapper, en.actions.approve)?.attributes('disabled')).toBeDefined()
    },
  )

  it('sends no edit when the amount is left as proposed', async () => {
    const { wrapper, action } = await openEditor()
    approveAction.mockResolvedValue({ action: approved(action, paymentResult), replayed: false })

    await press(wrapper, en.actions.approve)

    expect(approveAction.mock.calls[0][2]).toBeUndefined()
  })

  it('warns that the amount is above the balance and needs the box ticked before it can be approved', async () => {
    const { wrapper, action } = await openEditor()
    previewAction.mockRejectedValueOnce(new ApiError(422, 'Unprocessable', 'amount_exceeds_outstanding'))
    previewAction.mockResolvedValueOnce(
      paymentPreviewView(20000, { outstandingAfter: 0, overpaymentCredit: 5600 }),
    )
    approveAction.mockResolvedValue({
      action: approved(action, { ...paymentResult, amount: 20000, applied: 14400, overpaymentCredit: 5600, outstandingAfter: 0 }),
      replayed: false,
    })

    await typeAmount(wrapper, '20000')
    await updatePreview(wrapper)

    const warning = wrapper.get('.warning')
    expect(warning.text()).toContain(en.actions.editor.exceedsTitle)
    expect(warning.text()).toContain('SAR 20,000.00')
    expect(wrapper.find('tbody').exists()).toBe(false)
    const approve = () => buttonLabelled(wrapper, en.actions.approve)
    expect(approve()?.attributes('disabled')).toBeDefined()
    const checkbox = wrapper.get('input[type="checkbox"]')
    expect(checkbox.element).toHaveProperty('checked', false)

    await checkbox.setValue(true)
    await flushPromises()

    expect(previewAction).toHaveBeenLastCalledWith(action.id, { amount: 20000, overridePayment: true })
    expect(wrapper.find('.warning').exists()).toBe(false)
    expect(wrapper.get('.credit').text()).toContain('SAR 5,600.00')
    expect(approve()?.attributes('disabled')).toBeUndefined()

    await press(wrapper, en.actions.approve)

    expect(approveAction).toHaveBeenCalledWith(action.id, expect.any(String), { amount: 20000, overridePayment: true })
  })

  it('clears the overpayment choice once the amount is back below the balance, and sends no flag', async () => {
    const { wrapper, action } = await openEditor()
    const exceeds = new ApiError(422, 'Unprocessable', 'amount_exceeds_outstanding')
    previewAction.mockRejectedValueOnce(exceeds)
    previewAction.mockResolvedValueOnce(paymentPreviewView(20000, { overpaymentCredit: 5600, outstandingAfter: 0 }))
    previewAction.mockResolvedValueOnce(paymentPreviewView(1000))
    previewAction.mockRejectedValueOnce(exceeds)
    approveAction.mockResolvedValue({ action: approved(action, paymentResult), replayed: false })

    await typeAmount(wrapper, '20000')
    await updatePreview(wrapper)
    await wrapper.get('input[type="checkbox"]').setValue(true)
    await flushPromises()
    await typeAmount(wrapper, '1000')
    await updatePreview(wrapper)

    // Below the balance the choice means nothing, so it is cleared and the box is gone.
    expect(wrapper.find('input[type="checkbox"]').exists()).toBe(false)
    await press(wrapper, en.actions.approve)
    expect(approveAction.mock.calls[0][2]).toEqual({ amount: 1000, overridePayment: false })

    expect(previewAction.mock.calls.map((call) => call[1])).toEqual([
      { amount: 20000, overridePayment: false },
      { amount: 20000, overridePayment: true },
      { amount: 1000, overridePayment: true },
    ])
  })

  it('shows the warning again for a larger amount that follows a smaller one', async () => {
    const { wrapper, action } = await openEditor()
    const exceeds = new ApiError(422, 'Unprocessable', 'amount_exceeds_outstanding')
    previewAction.mockRejectedValueOnce(exceeds)
    previewAction.mockResolvedValueOnce(paymentPreviewView(20000, { overpaymentCredit: 5600, outstandingAfter: 0 }))
    previewAction.mockResolvedValueOnce(paymentPreviewView(1000))
    previewAction.mockRejectedValueOnce(exceeds)

    await typeAmount(wrapper, '20000')
    await updatePreview(wrapper)
    await wrapper.get('input[type="checkbox"]').setValue(true)
    await flushPromises()
    await typeAmount(wrapper, '1000')
    await updatePreview(wrapper)
    await typeAmount(wrapper, '20000')
    await updatePreview(wrapper)

    expect(previewAction).toHaveBeenLastCalledWith(action.id, { amount: 20000, overridePayment: false })
    expect(wrapper.find('.warning').exists()).toBe(true)
    expect(wrapper.get('input[type="checkbox"]').element).toHaveProperty('checked', false)
    expect(buttonLabelled(wrapper, en.actions.approve)?.attributes('disabled')).toBeDefined()
  })

  it('blocks approval when the proposed amount itself has become more than the balance', async () => {
    const { wrapper } = await openEditor()
    // Someone paid part of the rent after the proposal was made, so the unchanged amount is above the balance now.
    previewAction.mockRejectedValue(new ApiError(422, 'Unprocessable', 'amount_exceeds_outstanding'))

    await updatePreview(wrapper)

    expect(wrapper.get('.warning').text()).toContain('SAR 5,000.00')
    expect(buttonLabelled(wrapper, en.actions.approve)?.attributes('disabled')).toBeDefined()
  })

  it('does not quote the balance the proposal was made against, which may have changed since', async () => {
    const { wrapper } = await openEditor()
    // The proposal was made when SAR 14,400.00 was owed; the server refuses against what is owed now.
    previewAction.mockRejectedValue(new ApiError(422, 'Unprocessable', 'amount_exceeds_outstanding'))

    await updatePreview(wrapper)

    const warning = wrapper.get('.warning').text()
    expect(warning).toContain('SAR 5,000.00')
    expect(warning).not.toContain('14,400')
  })

  it('words a refused preview in the viewer language and keeps the previous diff', async () => {
    const { wrapper } = await openEditor()
    previewAction.mockRejectedValue(new ApiError(422, 'Unprocessable', 'nothing_outstanding'))

    await typeAmount(wrapper, '100')
    await updatePreview(wrapper)

    expect(wrapper.get('[role="alert"]').text()).toBe(en.actions.errors.nothing_outstanding)
    expect(wrapper.find('tbody').exists()).toBe(true)
  })

  it('leaves the proposal as it was when the editor is cancelled', async () => {
    const { wrapper, action } = await openEditor()
    previewAction.mockResolvedValue(paymentPreviewView(1000))
    approveAction.mockResolvedValue({ action: approved(action, paymentResult), replayed: false })

    await typeAmount(wrapper, '1000')
    await updatePreview(wrapper)
    await press(wrapper, en.actions.cancel)

    expect(wrapper.find('form').exists()).toBe(false)
    expect(wrapper.get('.outstanding').text()).toContain('SAR 9,400.00')
    await press(wrapper, en.actions.approve)
    expect(approveAction.mock.calls[0][2]).toBeUndefined()
  })
})

describe('ApprovalCard when the server refuses', () => {
  it('shows that someone else decided (409) and brings the card up to date', async () => {
    const action = paymentAction()
    const { wrapper } = await show(action)
    approveAction.mockRejectedValue(new ApiError(409, 'Conflict', 'action_already_decided'))
    fetchAction.mockResolvedValue(approved(action, paymentResult, 'visitor-99999999'))

    await press(wrapper, en.actions.approve)

    expect(fetchAction).toHaveBeenCalledWith(action.id)
    expect(wrapper.get('[role="alert"]').text()).toBe(en.actions.errors.action_already_decided)
    expect(wrapper.get('[role="group"]').classes()).toContain('approved')
    expect(wrapper.find('.buttons').exists()).toBe(false)
  })

  it('shows that the proposal expired (410)', async () => {
    const action = paymentAction()
    const { wrapper } = await show(action)
    approveAction.mockRejectedValue(new ApiError(410, 'Gone', 'action_expired'))
    fetchAction.mockResolvedValue({ ...action, status: 'EXPIRED' })

    await press(wrapper, en.actions.approve)

    expect(wrapper.get('[role="alert"]').text()).toBe(en.actions.errors.action_expired)
    const card = wrapper.get('[role="group"]')
    expect(card.text()).toContain(en.actions.status.EXPIRED)
    expect(card.find('.buttons').exists()).toBe(false)
    expect(streamConfirmation).not.toHaveBeenCalled()
  })

  it('shows why a proposal can no longer be made (422), as failed, and has the assistant confirm it', async () => {
    const action = paymentAction()
    const { wrapper } = await show(action)
    approveAction.mockRejectedValue(new ApiError(422, 'Unprocessable', 'nothing_outstanding'))
    fetchAction.mockResolvedValue({
      ...action,
      status: 'FAILED',
      actor: 'visitor-1a2b3c4d',
      decisionReason: 'Unit B-203 has no rent outstanding, so there is nothing to record a payment against.',
      result: { code: 'nothing_outstanding', message: 'English text from the server' },
    })

    await press(wrapper, en.actions.approve)

    const card = wrapper.get('[role="group"]')
    expect(card.classes()).toContain('failed')
    expect(card.get('.outcome').text()).toContain(en.actions.outcome.failed)
    expect(card.get('.outcome').text()).toContain(en.actions.errors.nothing_outstanding)
    expect(card.text()).not.toContain('English text from the server')
    expect(streamConfirmation).toHaveBeenCalledTimes(1)
  })

  it('words a failure in Arabic too', async () => {
    const action = paymentAction()
    const { wrapper } = await show({
      ...action,
      status: 'FAILED',
      result: { code: 'amount_exceeds_outstanding', message: 'English text' },
    } as ActionView)

    i18n.global.locale.value = 'ar'
    await nextTick()

    expect(wrapper.get('.outcome').text()).toContain(ar.actions.errors.amount_exceeds_outstanding)
    expect(wrapper.get('.outcome').text()).toContain(ar.actions.outcome.failed)
  })

  it('keeps a refused edit open so it can be corrected (422 while still proposed)', async () => {
    const action = paymentAction()
    const { wrapper } = await show(action)
    approveAction.mockRejectedValue(new ApiError(422, 'Unprocessable', 'invalid_amount'))
    fetchAction.mockResolvedValue(action)

    await press(wrapper, en.actions.approve)

    expect(wrapper.get('[role="alert"]').text()).toBe(en.actions.errors.invalid_amount)
    expect(buttonLabelled(wrapper, en.actions.approve)?.attributes('disabled')).toBeUndefined()
    expect(wrapper.get('[role="group"]').classes()).not.toContain('failed')
  })

  it('asks the viewer to wait when decisions are rate limited (429) and leaves the card open', async () => {
    const { wrapper } = await show(paymentAction())
    approveAction.mockRejectedValue(new ApiError(429, 'Too Many Requests', 'rate_limited'))

    await press(wrapper, en.actions.approve)

    expect(wrapper.get('[role="alert"]').text()).toBe(en.actions.errors.rate_limited)
    expect(fetchAction).not.toHaveBeenCalled()
    expect(buttonLabelled(wrapper, en.actions.approve)?.attributes('disabled')).toBeUndefined()
  })

  it('words an unknown code by its status and shows no server text', async () => {
    const { wrapper } = await show(paymentAction())
    approveAction.mockRejectedValue(new ApiError(429, 'The server says wait', 'something_new'))

    await press(wrapper, en.actions.approve)

    expect(wrapper.get('[role="alert"]').text()).toBe(en.actions.errorStatus.rateLimited)
    expect(wrapper.text()).not.toContain('The server says wait')
  })

  it('says the proposal no longer exists (404) and offers no more decisions', async () => {
    const { wrapper } = await show(paymentAction())
    approveAction.mockRejectedValue(new ApiError(404, 'Not Found', 'action_not_found'))

    await press(wrapper, en.actions.approve)

    expect(wrapper.get('[role="alert"]').text()).toBe(en.actions.errors.action_not_found)
    expect(wrapper.find('.buttons').exists()).toBe(false)
  })

  it('words a failed rejection the same way', async () => {
    const action = paymentAction()
    const { wrapper } = await show(action)
    rejectAction.mockRejectedValue(new ApiError(409, 'Conflict', 'action_already_decided'))
    fetchAction.mockResolvedValue({ ...action, status: 'REJECTED', actor: 'visitor-99999999' })

    await press(wrapper, en.actions.reject)
    await press(wrapper, en.actions.confirmReject)

    expect(wrapper.get('[role="alert"]').text()).toBe(en.actions.errors.action_already_decided)
    expect(wrapper.get('[role="group"]').classes()).toContain('rejected')
  })
})
