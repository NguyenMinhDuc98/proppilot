import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { nextTick } from 'vue'
import { ApiError } from '../api/http'
import { i18n } from '../i18n'
import ar from '../i18n/ar'
import en from '../i18n/en'
import { auditEntry, maintenanceAction, paymentAction } from '../test/actionFixtures'
import ActivityView from './ActivityView.vue'
import * as appInfo from '../composables/useAppInfo'

const fetchActions = vi.fn()
const fetchAudit = vi.fn()
vi.mock('../composables/useAppInfo', async () => {
  const { ref } = await import('vue')
  const info = ref<{ demoReset?: boolean } | null>(null)
  return { infoForTest: info, useAppInfo: () => ({ info }) }
})

const infoForTest = (appInfo as unknown as { infoForTest: { value: { demoReset?: boolean } | null } }).infoForTest

vi.mock('../api/actions', () => ({
  fetchActions: () => fetchActions(),
  fetchAudit: (...args: unknown[]) => fetchAudit(...args),
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
  infoForTest.value = null
  fetchActions.mockReset()
  fetchAudit.mockReset()
})

afterEach(() => {
  i18n.global.locale.value = 'en'
})

describe('ActivityView', () => {
  it('promises a nightly reset only when the deployment can be reset', async () => {
    fetchActions.mockResolvedValue([])
    infoForTest.value = { demoReset: true }

    const wrapper = await render()

    expect(wrapper.get('[role="note"]').text()).toBe(en.activity.banner)
    expect(en.activity.banner).toBe('Demo data resets every night; other visitors may see these entries.')

    i18n.global.locale.value = 'ar'
    await nextTick()
    expect(wrapper.get('[role="note"]').text()).toBe(ar.activity.banner)
  })

  it('does not promise a reset when the deployment cannot be reset, or before it is known', async () => {
    fetchActions.mockResolvedValue([])

    for (const info of [{ demoReset: false }, {}, null]) {
      infoForTest.value = info
      const wrapper = await render()

      expect(wrapper.get('[role="note"]').text()).toBe(en.activity.bannerNoReset)
      expect(wrapper.get('[role="note"]').text()).not.toContain('every night')
    }

    i18n.global.locale.value = 'ar'
    infoForTest.value = { demoReset: false }
    const arabic = await render()
    expect(arabic.get('[role="note"]').text()).toBe(ar.activity.bannerNoReset)
  })

  it('lists each action with its status, kind, summary, time and who decided', async () => {
    fetchActions.mockResolvedValue([approvedPayment, waitingRequest])

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
    const wrapper = await render()

    i18n.global.locale.value = 'ar'
    await nextTick()

    const item = wrapper.get('li.item')
    expect(item.text()).toContain(approvedPayment.summaryAr)
    expect(item.text()).toContain(ar.actions.status.APPROVED)
    expect(item.text()).toContain(ar.activity.auditTrail)
  })

  it('reads the audit trail of an action only when it is opened, then shows its entries oldest first', async () => {
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
    ])
    const wrapper = await render()
    const toggle = wrapper.get('button.toggle')
    const trail = wrapper.get('.trail')

    expect(fetchAudit).not.toHaveBeenCalled()
    expect(toggle.attributes('aria-expanded')).toBe('false')
    expect(toggle.text()).toBe(en.activity.auditTrail)
    expect(trail.attributes('style')).toContain('display: none')

    await toggle.trigger('click')
    await flushPromises()

    expect(fetchAudit).toHaveBeenCalledTimes(1)
    expect(fetchAudit).toHaveBeenCalledWith(approvedPayment.id)
    expect(toggle.attributes('aria-expanded')).toBe('true')
    expect(toggle.attributes('aria-controls')).toBe(trail.attributes('id'))
    expect(toggle.text()).toBe(`${en.activity.auditTrail} (3)`)
    expect(trail.attributes('style') ?? '').not.toContain('display: none')
    const heads = trail.findAll('.entry-head').map((head) => head.text())
    expect(heads).toHaveLength(3)
    expect(heads[0]).toContain(en.activity.events.PROPOSED)
    expect(heads[0]).toContain(en.activity.actors.agent)
    expect(heads[1]).toContain(en.activity.events.EDITED)
    expect(heads[2]).toContain(en.activity.events.APPROVED)
    expect(heads[2]).toContain('visitor-1a2b3c4d')
  })

  it('asks for the trail of each action by itself, so an older action is as whole as a new one', async () => {
    fetchActions.mockResolvedValue([waitingRequest, approvedPayment])
    fetchAudit.mockImplementation(async (id: string) => [
      auditEntry({ id: id === approvedPayment.id ? 1 : 2, actionId: id, event: id === approvedPayment.id ? 'PROPOSED' : 'REJECTED' }),
    ])
    const wrapper = await render()

    for (const toggle of wrapper.findAll('button.toggle')) await toggle.trigger('click')
    await flushPromises()

    expect(fetchAudit.mock.calls.map((call) => call[0])).toEqual([waitingRequest.id, approvedPayment.id])
    const items = wrapper.findAll('li.item')
    expect(items[0].get('.entry-head').text()).toContain(en.activity.events.REJECTED)
    expect(items[1].get('.entry-head').text()).toContain(en.activity.events.PROPOSED)
  })

  it('reads the trail once, however often it is closed and opened again', async () => {
    fetchActions.mockResolvedValue([approvedPayment])
    fetchAudit.mockResolvedValue([auditEntry()])
    const wrapper = await render()
    const toggle = wrapper.get('button.toggle')

    await toggle.trigger('click')
    await flushPromises()
    await toggle.trigger('click')
    await toggle.trigger('click')
    await flushPromises()

    expect(fetchAudit).toHaveBeenCalledTimes(1)
  })

  it('says that the trail is being read, and what it holds once it has arrived', async () => {
    fetchActions.mockResolvedValue([approvedPayment])
    let arrive: (entries: unknown[]) => void = () => {}
    fetchAudit.mockReturnValue(new Promise((resolve) => (arrive = resolve)))
    const wrapper = await render()

    await wrapper.get('button.toggle').trigger('click')

    expect(wrapper.get('.trail [role="status"]').text()).toBe(en.activity.auditLoading)
    expect(wrapper.get('.trail').text()).not.toContain(en.activity.auditEmpty)

    arrive([auditEntry()])
    await flushPromises()

    expect(wrapper.find('.trail [role="status"]').exists()).toBe(false)
    expect(wrapper.findAll('.entry')).toHaveLength(1)
  })

  it('says when the trail could not be read, instead of claiming that there is none, and can try again', async () => {
    fetchActions.mockResolvedValue([approvedPayment])
    fetchAudit.mockRejectedValueOnce(new TypeError('Failed to fetch'))
    fetchAudit.mockResolvedValueOnce([auditEntry()])
    const wrapper = await render()

    await wrapper.get('button.toggle').trigger('click')
    await flushPromises()

    expect(wrapper.get('.trail [role="alert"]').text()).toContain(en.activity.auditError)
    expect(wrapper.get('.trail').text()).not.toContain(en.activity.auditEmpty)
    expect(wrapper.get('button.toggle').text()).toBe(en.activity.auditTrail)

    await wrapper.get('.trail [role="alert"] button').trigger('click')
    await flushPromises()

    expect(wrapper.find('.trail [role="alert"]').exists()).toBe(false)
    expect(wrapper.findAll('.entry')).toHaveLength(1)
  })

  it('shows before and after as pretty JSON in a left-to-right monospace block', async () => {
    fetchActions.mockResolvedValue([approvedPayment])
    fetchAudit.mockResolvedValue([
      auditEntry({ event: 'EDITED', before: { amount: 5000 }, after: { amount: 4800 }, note: 'Fixed <b>amount</b>' }),
    ])
    const wrapper = await render()

    await wrapper.get('button.toggle').trigger('click')
    await flushPromises()

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

  it('lets a keyboard user scroll a long block: it can be focused and is named by its event and side', async () => {
    fetchActions.mockResolvedValue([approvedPayment])
    fetchAudit.mockResolvedValue([auditEntry({ event: 'EDITED', before: { amount: 5000 }, after: { amount: 4800 } })])
    const wrapper = await render()

    await wrapper.get('button.toggle').trigger('click')
    await flushPromises()

    const [before, after] = wrapper.findAll('pre.mono')
    expect(before.attributes('tabindex')).toBe('0')
    expect(before.attributes('role')).toBe('region')
    expect(before.attributes('aria-label')).toBe(`${en.activity.events.EDITED}: ${en.activity.before}`)
    expect(after.attributes('tabindex')).toBe('0')
    expect(after.attributes('role')).toBe('region')
    expect(after.attributes('aria-label')).toBe(`${en.activity.events.EDITED}: ${en.activity.after}`)

    i18n.global.locale.value = 'ar'
    await nextTick()
    expect(before.attributes('aria-label')).toBe(`${ar.activity.events.EDITED}: ${ar.activity.before}`)
  })

  it('leaves out the side of an entry that did not exist', async () => {
    fetchActions.mockResolvedValue([approvedPayment])
    fetchAudit.mockResolvedValue([auditEntry({ before: null, after: { kind: 'RECORD_PAYMENT' } })])
    const wrapper = await render()

    await wrapper.get('button.toggle').trigger('click')
    await flushPromises()

    expect(wrapper.findAll('pre.mono')).toHaveLength(1)
    expect(wrapper.get('.state > span').text()).toBe(en.activity.after)
  })

  it('says so when the server has no audit entries for an action', async () => {
    fetchActions.mockResolvedValue([approvedPayment])
    fetchAudit.mockResolvedValue([])
    const wrapper = await render()

    await wrapper.get('button.toggle').trigger('click')
    await flushPromises()

    expect(wrapper.get('.trail').text()).toBe(en.activity.auditEmpty)
    expect(wrapper.get('button.toggle').text()).toBe(`${en.activity.auditTrail} (0)`)
  })

  it('shows an empty state when nothing has been proposed yet', async () => {
    fetchActions.mockResolvedValue([])

    const wrapper = await render()

    expect(wrapper.text()).toContain(en.activity.empty)
    expect(wrapper.find('li.item').exists()).toBe(false)
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
  })

  it('shows an error state with a retry that loads the activity again', async () => {
    fetchActions.mockRejectedValueOnce(new ApiError(503, 'Unavailable'))
    const wrapper = await render()

    expect(wrapper.get('[role="alert"]').text()).toContain(en.activity.error)
    expect(wrapper.find('li.item').exists()).toBe(false)

    fetchActions.mockResolvedValueOnce([approvedPayment])
    await wrapper.get('[role="alert"] button').trigger('click')
    await flushPromises()

    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(wrapper.findAll('li.item')).toHaveLength(1)
  })

  it('reloads when asked to refresh', async () => {
    fetchActions.mockResolvedValue([])
    const wrapper = await render()
    fetchActions.mockResolvedValue([waitingRequest])

    await wrapper.get('.head button').trigger('click')
    await flushPromises()

    expect(fetchActions).toHaveBeenCalledTimes(2)
    expect(wrapper.findAll('li.item')).toHaveLength(1)
  })

  describe('refreshing', () => {
    const decidedLater = { ...approvedPayment, decidedAt: '2026-10-10T15:09:00Z' }

    it('reads an open trail again, so an entry that was added meanwhile appears', async () => {
      fetchActions.mockResolvedValue([waitingRequest])
      fetchAudit.mockResolvedValueOnce([auditEntry({ id: 1, actionId: waitingRequest.id, event: 'PROPOSED' })])
      const wrapper = await render()
      await wrapper.get('button.toggle').trigger('click')
      await flushPromises()
      expect(wrapper.findAll('.entry')).toHaveLength(1)
      fetchActions.mockResolvedValue([{ ...waitingRequest, status: 'REJECTED' }])
      fetchAudit.mockResolvedValueOnce([
        auditEntry({ id: 2, actionId: waitingRequest.id, event: 'REJECTED' }),
        auditEntry({ id: 1, actionId: waitingRequest.id, event: 'PROPOSED' }),
      ])

      await wrapper.get('.head button').trigger('click')
      await flushPromises()

      expect(fetchAudit).toHaveBeenCalledTimes(2)
      expect(wrapper.findAll('.entry')).toHaveLength(2)
      expect(wrapper.get('button.toggle').attributes('aria-expanded')).toBe('true')
    })

    it('reads a closed trail only when it is opened next, and then as it is by then', async () => {
      fetchActions.mockResolvedValue([approvedPayment])
      fetchAudit.mockResolvedValue([auditEntry()])
      const wrapper = await render()
      await wrapper.get('button.toggle').trigger('click')
      await flushPromises()
      await wrapper.get('button.toggle').trigger('click')
      fetchActions.mockResolvedValue([decidedLater])

      await wrapper.get('.head button').trigger('click')
      await flushPromises()

      expect(fetchAudit).toHaveBeenCalledTimes(1)
      expect(wrapper.get('button.toggle').text()).toBe(en.activity.auditTrail)

      await wrapper.get('button.toggle').trigger('click')
      await flushPromises()

      expect(fetchAudit).toHaveBeenCalledTimes(2)
    })

    it('does not let a read that was started before the refresh overwrite the one made after it', async () => {
      fetchActions.mockResolvedValue([approvedPayment])
      const arrivals: ((entries: unknown[]) => void)[] = []
      fetchAudit.mockImplementation(() => new Promise((resolve) => arrivals.push(resolve)))
      const wrapper = await render()
      await wrapper.get('button.toggle').trigger('click')
      fetchActions.mockResolvedValue([decidedLater])
      await wrapper.get('.head button').trigger('click')
      await flushPromises()
      expect(arrivals).toHaveLength(2)

      arrivals[1]([auditEntry({ id: 2, event: 'APPROVED' }), auditEntry({ id: 1, event: 'PROPOSED' })])
      await flushPromises()
      arrivals[0]([auditEntry({ id: 1, event: 'PROPOSED' })])
      await flushPromises()

      expect(wrapper.findAll('.entry')).toHaveLength(2)
    })
  })
})
