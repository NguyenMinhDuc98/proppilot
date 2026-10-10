import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { nextTick } from 'vue'
import { ApiError } from '../api/http'
import { i18n } from '../i18n'
import ar from '../i18n/ar'
import en from '../i18n/en'
import { auditEntry, maintenanceAction, paymentAction } from '../test/actionFixtures'
import ActivityView from './ActivityView.vue'

const fetchActions = vi.fn()
const fetchAudit = vi.fn()
vi.mock('../api/actions', () => ({
  fetchActions: () => fetchActions(),
  fetchAudit: () => fetchAudit(),
}))

async function render() {
  const wrapper = mount(ActivityView, { global: { plugins: [i18n] } })
  await flushPromises()
  return wrapper
}

const approvedPayment = paymentAction({
  status: 'APPROVED',
  actor: 'visitor-1a2b3c4d',
  decidedAt: '2026-10-10T15:02:00Z',
})
const waitingRequest = maintenanceAction()

beforeEach(() => {
  fetchActions.mockReset()
  fetchAudit.mockReset()
})

afterEach(() => {
  i18n.global.locale.value = 'en'
})

describe('ActivityView', () => {
  it('tells visitors that the demo resets every night and that others see the entries', async () => {
    fetchActions.mockResolvedValue([])
    fetchAudit.mockResolvedValue([])

    const wrapper = await render()

    expect(wrapper.get('[role="note"]').text()).toBe(en.activity.banner)
    expect(en.activity.banner).toBe('Demo data resets every night; other visitors may see these entries.')

    i18n.global.locale.value = 'ar'
    await nextTick()
    expect(wrapper.get('[role="note"]').text()).toBe(ar.activity.banner)
  })

  it('lists each action with its status, kind, summary, time and who decided', async () => {
    fetchActions.mockResolvedValue([approvedPayment, waitingRequest])
    fetchAudit.mockResolvedValue([])

    const wrapper = await render()

    const items = wrapper.findAll('li.item')
    expect(items).toHaveLength(2)
    expect(items[0].text()).toContain(en.actions.status.APPROVED)
    expect(items[0].text()).toContain(en.actions.kinds.RECORD_PAYMENT)
    expect(items[0].text()).toContain(approvedPayment.summaryEn)
    expect(items[0].text()).toContain('visitor-1a2b3c4d')
    expect(items[0].get('time').attributes('datetime')).toBe(approvedPayment.createdAt)
    expect(items[1].text()).toContain(en.actions.status.PROPOSED)
    expect(items[1].text()).toContain(en.actions.kinds.CREATE_MAINTENANCE_REQUEST)
    expect(items[1].text()).toContain(en.activity.undecided)
  })

  it('shows the summary in the language of the page', async () => {
    fetchActions.mockResolvedValue([approvedPayment])
    fetchAudit.mockResolvedValue([])
    const wrapper = await render()

    i18n.global.locale.value = 'ar'
    await nextTick()

    const item = wrapper.get('li.item')
    expect(item.text()).toContain(approvedPayment.summaryAr)
    expect(item.text()).toContain(ar.actions.status.APPROVED)
    expect(item.text()).toContain(ar.activity.auditTrail)
  })

  it('keeps the audit trail closed until it is asked for, then shows its entries oldest first', async () => {
    fetchActions.mockResolvedValue([approvedPayment])
    // The server answers newest first.
    fetchAudit.mockResolvedValue([
      auditEntry({
        id: 3, event: 'APPROVED', actor: 'visitor-1a2b3c4d', at: '2026-10-10T15:02:00Z',
        entityType: 'LEASE_PAYMENTS', entityId: '77',
        before: { payments: [{ paidAmount: 0 }] }, after: { payments: [{ paidAmount: 4800 }] },
      }),
      auditEntry({ id: 2, event: 'EDITED', actor: 'visitor-1a2b3c4d', at: '2026-10-10T15:01:00Z', before: { amount: 5000 }, after: { amount: 4800 } }),
      auditEntry({ id: 1, event: 'PROPOSED', actor: 'agent', at: '2026-10-10T15:00:00Z', before: null, after: { kind: 'RECORD_PAYMENT' } }),
      auditEntry({ id: 9, event: 'PROPOSED', actionId: 'another-action' }),
    ])
    const wrapper = await render()
    const toggle = wrapper.get('button.toggle')
    const trail = wrapper.get('.trail')

    expect(toggle.attributes('aria-expanded')).toBe('false')
    expect(toggle.text()).toContain('(3)')
    expect(trail.attributes('style')).toContain('display: none')

    await toggle.trigger('click')

    expect(toggle.attributes('aria-expanded')).toBe('true')
    expect(toggle.attributes('aria-controls')).toBe(trail.attributes('id'))
    expect(trail.attributes('style') ?? '').not.toContain('display: none')
    const heads = trail.findAll('.entry-head').map((head) => head.text())
    expect(heads).toHaveLength(3)
    expect(heads[0]).toContain(en.activity.events.PROPOSED)
    expect(heads[0]).toContain(en.activity.actors.agent)
    expect(heads[1]).toContain(en.activity.events.EDITED)
    expect(heads[2]).toContain(en.activity.events.APPROVED)
    expect(heads[2]).toContain('visitor-1a2b3c4d')
  })

  it('shows before and after as pretty JSON in a left-to-right monospace block', async () => {
    fetchActions.mockResolvedValue([approvedPayment])
    fetchAudit.mockResolvedValue([
      auditEntry({ event: 'EDITED', before: { amount: 5000 }, after: { amount: 4800 }, note: 'Fixed <b>amount</b>' }),
    ])
    const wrapper = await render()

    await wrapper.get('button.toggle').trigger('click')

    const blocks = wrapper.findAll('pre.mono')
    expect(blocks.map((block) => block.text())).toEqual([
      JSON.stringify({ amount: 5000 }, null, 2),
      JSON.stringify({ amount: 4800 }, null, 2),
    ])
    expect(blocks.every((block) => block.attributes('dir') === 'ltr')).toBe(true)
    const labels = wrapper.findAll('.state > span').map((label) => label.text())
    expect(labels).toEqual([en.activity.before, en.activity.after])
    expect(wrapper.get('.note').text()).toContain('Fixed <b>amount</b>')
    expect(wrapper.find('.note b').exists()).toBe(false)
  })

  it('leaves out the side of an entry that did not exist', async () => {
    fetchActions.mockResolvedValue([approvedPayment])
    fetchAudit.mockResolvedValue([auditEntry({ before: null, after: { kind: 'RECORD_PAYMENT' } })])
    const wrapper = await render()

    await wrapper.get('button.toggle').trigger('click')

    expect(wrapper.findAll('pre.mono')).toHaveLength(1)
    expect(wrapper.get('.state > span').text()).toBe(en.activity.after)
  })

  it('says so when an action has no audit entries', async () => {
    fetchActions.mockResolvedValue([approvedPayment])
    fetchAudit.mockResolvedValue([])
    const wrapper = await render()

    await wrapper.get('button.toggle').trigger('click')

    expect(wrapper.get('.trail').text()).toBe(en.activity.auditEmpty)
  })

  it('shows an empty state when nothing has been proposed yet', async () => {
    fetchActions.mockResolvedValue([])
    fetchAudit.mockResolvedValue([])

    const wrapper = await render()

    expect(wrapper.text()).toContain(en.activity.empty)
    expect(wrapper.find('li.item').exists()).toBe(false)
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
  })

  it('shows an error state with a retry that loads the activity again', async () => {
    fetchActions.mockRejectedValueOnce(new ApiError(503, 'Unavailable'))
    fetchAudit.mockResolvedValue([])
    const wrapper = await render()

    expect(wrapper.get('[role="alert"]').text()).toContain(en.activity.error)
    expect(wrapper.find('li.item').exists()).toBe(false)

    fetchActions.mockResolvedValueOnce([approvedPayment])
    await wrapper.get('[role="alert"] button').trigger('click')
    await flushPromises()

    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(wrapper.findAll('li.item')).toHaveLength(1)
  })

  it('shows an error when only the audit log cannot be read', async () => {
    fetchActions.mockResolvedValue([approvedPayment])
    fetchAudit.mockRejectedValue(new TypeError('Failed to fetch'))

    const wrapper = await render()

    expect(wrapper.get('[role="alert"]').text()).toContain(en.activity.error)
  })

  it('reloads when asked to refresh', async () => {
    fetchActions.mockResolvedValue([])
    fetchAudit.mockResolvedValue([])
    const wrapper = await render()
    fetchActions.mockResolvedValue([waitingRequest])

    await wrapper.get('.head button').trigger('click')
    await flushPromises()

    expect(fetchActions).toHaveBeenCalledTimes(2)
    expect(wrapper.findAll('li.item')).toHaveLength(1)
  })
})
