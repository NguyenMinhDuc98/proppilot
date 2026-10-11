import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { nextTick } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { AdminRun, AdminRunDetail } from '../api/types'
import { useAdminToken } from '../composables/adminToken'
import { i18n } from '../i18n'
import ar from '../i18n/ar'
import en from '../i18n/en'
import { adminRun, adminRunDetail, HAIKU, llmCall, SONNET } from '../test/traceFixtures'
import AdminTracesView from './AdminTracesView.vue'

const TOKEN = 'correct-horse-battery-staple-0123'
const PAGE = 25

interface Reply {
  status?: number
  body?: unknown
}

/**
 * What the server answers, by address: tests set these and read what was asked from `asked`. A reply that is a promise
 * is an answer that is still on its way until the test lets it go.
 */
let runsReply: (params: URLSearchParams) => Reply | Promise<Reply>
let detailReply: (id: string) => Reply | Promise<Reply>
const asked: { url: URL; headers: Record<string, string> }[] = []

function respond({ status = 200, body = [] }: Reply) {
  return Promise.resolve(new Response(status === 200 ? JSON.stringify(body) : '', { status, statusText: 'x' }))
}

function fetchStub(input: RequestInfo | URL, init?: RequestInit) {
  const url = new URL(String(input), 'http://localhost')
  asked.push({ url, headers: (init?.headers ?? {}) as Record<string, string> })
  if (url.pathname === '/api/stats') {
    return respond({ body: { byModel: [{ model: HAIKU, runs: 3, costUsd: 0.1 }, { model: SONNET, runs: 1, costUsd: 0.05 }] } })
  }
  if (url.pathname === '/api/admin/runs') return Promise.resolve(runsReply(url.searchParams)).then(respond)
  const match = /^\/api\/admin\/runs\/(.+)$/.exec(url.pathname)
  if (match) return Promise.resolve(detailReply(match[1])).then(respond)
  return respond({ status: 404 })
}

/** A reply the server holds back until `release` is called. */
function held(reply: Reply) {
  let release: () => void = () => {}
  const gate = new Promise<void>((resolve) => { release = resolve })
  return { reply: gate.then(() => reply), release }
}

const mounted: VueWrapper[] = []

async function render() {
  const wrapper = mount(AdminTracesView, { attachTo: document.body, global: { plugins: [i18n] } })
  mounted.push(wrapper)
  await flushPromises()
  return wrapper
}

async function unlock(wrapper: Awaited<ReturnType<typeof render>>, token = TOKEN) {
  await wrapper.get('input[type="password"]').setValue(token)
  await wrapper.get('form.unlock').trigger('submit')
  await flushPromises()
}

const adminCalls = () => asked.filter((call) => call.url.pathname.startsWith('/api/admin/'))
const lastRunsRequest = () => adminCalls().filter((call) => call.url.pathname === '/api/admin/runs').at(-1)?.url.searchParams
/** The drawer, or null while it is closed. It is attached to the body, outside the page. */
function drawer(): HTMLElement | null {
  const dialog = document.body.querySelector<HTMLElement>('[role="dialog"]')
  const overlay = dialog?.closest<HTMLElement>('.el-overlay')
  return dialog && overlay?.style.display !== 'none' ? dialog : null
}

const runAt = (minute: number, overrides: Partial<AdminRun> = {}) =>
  adminRun({
    id: `run-${minute}`,
    createdAt: `2026-10-11T08:${String(minute).padStart(2, '0')}:00Z`,
    ...overrides,
  })

beforeEach(() => {
  useAdminToken().forget()
  asked.length = 0
  runsReply = () => ({ body: [runAt(30)] })
  detailReply = () => ({ body: adminRunDetail() })
  vi.stubGlobal('fetch', vi.fn(fetchStub))
})

afterEach(() => {
  mounted.splice(0).forEach((wrapper) => wrapper.unmount())
  document.body.innerHTML = ''
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
  i18n.global.locale.value = 'en'
})

describe('the admin token', () => {
  it('asks for it in a password field before it asks the server anything', async () => {
    const wrapper = await render()

    const field = wrapper.get('input[type="password"]')
    expect(field.attributes('autocomplete')).toBe('off')
    expect(wrapper.get('label').text()).toBe(en.admin.tokenLabel)
    expect(wrapper.get('button[type="submit"]').attributes('disabled')).toBeDefined()
    expect(adminCalls()).toEqual([])
  })

  it('sends it in the X-Admin-Token header and in no address', async () => {
    const wrapper = await render()

    await unlock(wrapper)

    expect(adminCalls().length).toBeGreaterThan(0)
    for (const call of adminCalls()) expect(call.headers).toEqual({ 'X-Admin-Token': TOKEN })
    for (const call of asked) expect(call.url.href).not.toContain(TOKEN)
    expect(window.location.href).not.toContain(TOKEN)
  })

  it('does not send it with the request for the models, which is public', async () => {
    const wrapper = await render()

    await unlock(wrapper)

    const stats = asked.find((call) => call.url.pathname === '/api/stats')
    expect(stats?.headers).toEqual({})
  })

  it('never writes it to the console', async () => {
    const spies = (['log', 'info', 'warn', 'error', 'debug'] as const).map((method) => vi.spyOn(console, method))
    runsReply = () => ({ status: 500 })
    const wrapper = await render()

    await unlock(wrapper)
    await wrapper.get('.problem button').trigger('click')
    await flushPromises()

    for (const spy of spies) expect(JSON.stringify(spy.mock.calls)).not.toContain(TOKEN)
  })

  it('is kept for this tab, so a reload does not ask again', async () => {
    const first = await render()
    await unlock(first)
    expect(sessionStorage.getItem('proppilot.adminToken')).toBe(TOKEN)
    first.unmount()
    asked.length = 0

    await render()

    expect(document.querySelector('input[type="password"]')).toBeNull()
    expect(adminCalls()[0].headers).toEqual({ 'X-Admin-Token': TOKEN })
    expect(document.body.textContent).toContain('Which units in Riyadh are vacant')
  })

  it('is forgotten on request, and the form asks for it again', async () => {
    const wrapper = await render()
    await unlock(wrapper)

    const lock = wrapper.findAll('button').find((button) => button.text() === en.admin.lock)
    await lock?.trigger('click')

    expect(sessionStorage.getItem('proppilot.adminToken')).toBeNull()
    expect(wrapper.find('input[type="password"]').exists()).toBe(true)
    expect(wrapper.find('.list').exists()).toBe(false)
  })

  it('keeps the page locked when an older page that was on its way arrives after the token was forgotten', async () => {
    const older = held({ body: [runAt(10, { question: 'An older question' })] })
    runsReply = (params) => (params.has('before') ? older.reply : { body: Array.from({ length: PAGE }, (_, i) => runAt(59 - i)) })
    const wrapper = await render()
    await unlock(wrapper)
    await wrapper.get('.more').trigger('click')
    await flushPromises()

    await wrapper.findAll('button').find((button) => button.text() === en.admin.lock)?.trigger('click')
    older.release()
    await flushPromises()

    expect(wrapper.find('input[type="password"]').exists()).toBe(true)
    expect(wrapper.find('li.row').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('An older question')
    expect(wrapper.findAll('button').some((button) => button.text() === en.admin.lock)).toBe(false)
  })

  it('closes the run that was open when it is forgotten, and takes what was shown of it off the page', async () => {
    const detail = held({ body: adminRunDetail() })
    detailReply = () => detail.reply
    const wrapper = await render()
    await unlock(wrapper)
    await wrapper.get('li.row button').trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('Which units in Riyadh are vacant right now?')

    await wrapper.findAll('button').find((button) => button.text() === en.admin.lock)?.trigger('click')
    detail.release()
    await flushPromises()

    expect(drawer()).toBeNull()
    expect(document.body.textContent).not.toContain('Which units in Riyadh are vacant right now?')
  })

  it('is cleared from the field once it is sent', async () => {
    const wrapper = await render()
    await unlock(wrapper)
    await wrapper.findAll('button').find((button) => button.text() === en.admin.lock)?.trigger('click')

    expect((wrapper.get('input[type="password"]').element as HTMLInputElement).value).toBe('')
  })
})

describe('a server that refuses', () => {
  it('says the endpoints are disabled when it answers 404, and offers no form', async () => {
    runsReply = () => ({ status: 404 })
    const wrapper = await render()

    await unlock(wrapper)

    expect(wrapper.get('[role="alert"]').text()).toBe('Admin endpoints are disabled on this server')
    expect(en.admin.disabled).toBe('Admin endpoints are disabled on this server')
    expect(wrapper.find('input[type="password"]').exists()).toBe(false)
    expect(sessionStorage.getItem('proppilot.adminToken')).toBeNull()
  })

  it('says the token was not accepted when it answers 403, and asks for another', async () => {
    runsReply = () => ({ status: 403 })
    const wrapper = await render()

    await unlock(wrapper, 'a-wrong-token-that-is-long-enough')

    expect(wrapper.get('[role="alert"]').text()).toBe('Token not accepted')
    expect(en.admin.rejected).toBe('Token not accepted')
    expect(wrapper.find('input[type="password"]').exists()).toBe(true)
    expect(sessionStorage.getItem('proppilot.adminToken')).toBeNull()
  })

  it('accepts the next token after a refusal', async () => {
    let refusals = 1
    runsReply = () => (refusals-- > 0 ? { status: 403 } : { body: [runAt(30)] })
    const wrapper = await render()
    await unlock(wrapper, 'a-wrong-token-that-is-long-enough')

    await unlock(wrapper)

    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(wrapper.findAll('li.row')).toHaveLength(1)
  })

  it('offers to try again when the server fails, and lists the runs once it recovers', async () => {
    runsReply = () => ({ status: 500 })
    const wrapper = await render()
    await unlock(wrapper)

    expect(wrapper.get('[role="alert"]').text()).toContain(en.admin.error)

    runsReply = () => ({ body: [runAt(30)] })
    await wrapper.get('.problem button').trigger('click')
    await flushPromises()

    expect(wrapper.findAll('li.row')).toHaveLength(1)
  })

  it('says the same in Arabic', async () => {
    runsReply = () => ({ status: 404 })
    const wrapper = await render()
    await unlock(wrapper)

    i18n.global.locale.value = 'ar'
    await nextTick()

    expect(wrapper.get('[role="alert"]').text()).toBe(ar.admin.disabled)
  })
})

describe('the list of runs', () => {
  it('shows the time, status, model, reason, verdict, cost, latency, tools and the question of each run', async () => {
    runsReply = () => ({
      body: [
        runAt(30, {
          status: 'TRUNCATED',
          model: SONNET,
          routedReason: 'COMPLEX',
          guardrailStatus: 'RETRIED_FLAGGED',
          guardrailViolations: 1,
          toolNames: ['search_units', 'find_overdue_tenants'],
          costUsd: 0.0123,
          latencyMs: 6200,
          question: 'Which leases end soon?',
        }),
      ],
    })
    const wrapper = await render()
    await unlock(wrapper)

    const row = wrapper.get('li.row')
    expect(row.get('time').attributes('datetime')).toBe('2026-10-11T08:30:00Z')
    const pills = row.findAll('.pill').map((pill) => pill.text())
    expect(pills).toEqual([en.admin.statuses.TRUNCATED, en.admin.guardrail.RETRIED_FLAGGED, en.admin.routed.COMPLEX])
    expect(row.get('.question').text()).toBe('Which leases end soon?')
    const meta = row.findAll('.meta > *').map((part) => part.text())
    expect(meta).toEqual(['Claude Sonnet 4.5', '$0.012', '6.2 s', '2 tools'])
  })

  it('marks neither the default model nor a checked answer, which are the ordinary ones', async () => {
    runsReply = () => ({ body: [runAt(30, { routedReason: 'DEFAULT', guardrailStatus: 'OK' })] })
    const wrapper = await render()
    await unlock(wrapper)

    expect(wrapper.findAll('li.row .pill').map((pill) => pill.text())).toEqual([en.admin.statuses.OK])
  })

  it('cuts a long question short and keeps all of it for the detail', async () => {
    const question = `${'w'.repeat(200)} the end`
    runsReply = () => ({ body: [runAt(30, { question })] })
    const wrapper = await render()
    await unlock(wrapper)

    const shown = wrapper.get('li.row .question').text()
    expect(shown).toHaveLength(141)
    expect(shown.endsWith('…')).toBe(true)
    expect(shown).not.toContain('the end')
  })

  it('shows what a visitor typed as text, never as markup', async () => {
    runsReply = () => ({ body: [runAt(30, { question: '<img src=x onerror=alert(1)> who is late?' })] })
    const wrapper = await render()
    await unlock(wrapper)

    expect(wrapper.find('li.row img').exists()).toBe(false)
    expect(wrapper.get('li.row .question').text()).toContain('<img src=x')
  })

  it('says so when no run matches', async () => {
    runsReply = () => ({ body: [] })
    const wrapper = await render()
    await unlock(wrapper)

    expect(wrapper.text()).toContain(en.admin.empty)
    expect(wrapper.find('.more').exists()).toBe(false)
  })

  it('asks for one page and nothing else when no filter is set', async () => {
    const wrapper = await render()
    await unlock(wrapper)

    expect(Object.fromEntries(lastRunsRequest() as URLSearchParams)).toEqual({ limit: String(PAGE) })
  })
})

describe('the filters', () => {
  it('asks the server for the status, the flagged answers and the model that were chosen', async () => {
    const wrapper = await render()
    await unlock(wrapper)

    await wrapper.findAll('select')[0].setValue('ERROR')
    await flushPromises()
    expect(lastRunsRequest()?.get('status')).toBe('ERROR')

    await wrapper.get('input[type="checkbox"]').setValue(true)
    await flushPromises()
    expect(lastRunsRequest()?.get('guardrailFlagged')).toBe('true')

    await wrapper.findAll('select')[1].setValue(SONNET)
    await flushPromises()
    expect(Object.fromEntries(lastRunsRequest() as URLSearchParams)).toEqual({
      limit: String(PAGE), status: 'ERROR', guardrailFlagged: 'true', model: SONNET,
    })
  })

  it('asks again when a filter changes while the list is still loading, and shows only the list it chose', async () => {
    const unfiltered = held({ body: [runAt(30, { question: 'Without the filter' })] })
    runsReply = (params) => (params.has('status') ? { body: [runAt(5, { status: 'ERROR', question: 'With the filter' })] } : unfiltered.reply)
    const wrapper = await render()
    await unlock(wrapper)
    expect(wrapper.text()).toContain(en.admin.loading)

    await wrapper.findAll('select')[0].setValue('ERROR')
    await flushPromises()
    unfiltered.release()
    await flushPromises()

    expect(lastRunsRequest()?.get('status')).toBe('ERROR')
    expect(wrapper.findAll('li.row').map((row) => row.get('.question').text())).toEqual(['With the filter'])
  })

  it('offers the models the server has runs for', async () => {
    const wrapper = await render()
    await unlock(wrapper)

    const options = wrapper.findAll('select')[1].findAll('option').map((option) => option.text())
    expect(options).toEqual([en.admin.filters.allModels, HAIKU, SONNET])
  })

  it('starts the list again from its newest run when a filter changes', async () => {
    runsReply = (params) => ({ body: params.has('status') ? [runAt(5, { status: 'ERROR' })] : [runAt(30), runAt(20)] })
    const wrapper = await render()
    await unlock(wrapper)
    expect(wrapper.findAll('li.row')).toHaveLength(2)

    await wrapper.findAll('select')[0].setValue('ERROR')
    await flushPromises()

    expect(wrapper.findAll('li.row')).toHaveLength(1)
    expect(lastRunsRequest()?.has('before')).toBe(false)
  })

  it('names the statuses in Arabic', async () => {
    const wrapper = await render()
    await unlock(wrapper)

    i18n.global.locale.value = 'ar'
    await nextTick()

    const options = wrapper.findAll('select')[0].findAll('option').map((option) => option.text())
    expect(options).toEqual([ar.admin.filters.allStatuses, ...Object.values(ar.admin.statuses)])
  })
})

describe('older runs', () => {
  const fullPage = Array.from({ length: PAGE }, (_, i) => runAt(59 - i))

  it('offers to load older runs only when the page was full', async () => {
    runsReply = () => ({ body: fullPage })
    const wrapper = await render()
    await unlock(wrapper)
    expect(wrapper.find('.more').exists()).toBe(true)
  })

  it('does not offer it for a page that was not full', async () => {
    runsReply = () => ({ body: fullPage.slice(0, 3) })
    const wrapper = await render()
    await unlock(wrapper)

    expect(wrapper.find('.more').exists()).toBe(false)
  })

  it('asks for the runs before the oldest one shown, and adds them below', async () => {
    const older = [runAt(10, { question: 'An older question' })]
    runsReply = (params) => ({ body: params.has('before') ? older : fullPage })
    const wrapper = await render()
    await unlock(wrapper)

    await wrapper.get('.more').trigger('click')
    await flushPromises()

    expect(lastRunsRequest()?.get('before')).toBe(fullPage.at(-1)?.createdAt)
    const rows = wrapper.findAll('li.row')
    expect(rows).toHaveLength(PAGE + 1)
    expect(rows.at(-1)?.text()).toContain('An older question')
    expect(wrapper.find('.more').exists()).toBe(false)
  })

  it('keeps the filters while paging', async () => {
    runsReply = () => ({ body: fullPage })
    const wrapper = await render()
    await unlock(wrapper)
    await wrapper.get('input[type="checkbox"]').setValue(true)
    await flushPromises()

    await wrapper.get('.more').trigger('click')
    await flushPromises()

    expect(lastRunsRequest()?.get('guardrailFlagged')).toBe('true')
    expect(lastRunsRequest()?.has('before')).toBe(true)
  })

  it('keeps the runs it has when the next page cannot be loaded', async () => {
    runsReply = (params) => (params.has('before') ? { status: 500 } : { body: fullPage })
    const wrapper = await render()
    await unlock(wrapper)

    await wrapper.get('.more').trigger('click')
    await flushPromises()

    expect(wrapper.findAll('li.row')).toHaveLength(PAGE)
    expect(wrapper.get('.problem').text()).toContain(en.admin.error)
  })
})

describe('older runs that could not be loaded', () => {
  const fullPage = Array.from({ length: PAGE }, (_, i) => runAt(59 - i))

  it('stop being reported once the list is loaded again for another filter', async () => {
    runsReply = (params) => (params.has('before') ? { status: 500 } : { body: fullPage })
    const wrapper = await render()
    await unlock(wrapper)
    await wrapper.get('.more').trigger('click')
    await flushPromises()
    expect(wrapper.find('.problem').exists()).toBe(true)

    await wrapper.findAll('select')[0].setValue('ERROR')
    await flushPromises()

    expect(wrapper.findAll('li.row')).toHaveLength(PAGE)
    expect(wrapper.find('.problem').exists()).toBe(false)
  })

  it('are not reported on the list the next unlock loads', async () => {
    runsReply = (params) => (params.has('before') ? { status: 500 } : { body: fullPage })
    const wrapper = await render()
    await unlock(wrapper)
    await wrapper.get('.more').trigger('click')
    await flushPromises()
    await wrapper.findAll('button').find((button) => button.text() === en.admin.lock)?.trigger('click')

    await unlock(wrapper)

    expect(wrapper.findAll('li.row')).toHaveLength(PAGE)
    expect(wrapper.find('.problem').exists()).toBe(false)
  })
})

describe('the detail of a run', () => {
  async function open(detail: AdminRunDetail = adminRunDetail()) {
    detailReply = () => ({ body: detail })
    const wrapper = await render()
    await unlock(wrapper)
    await wrapper.get('li.row button').trigger('click')
    await flushPromises()
    return wrapper
  }

  it('reads the run with the token in a header and shows its question in full', async () => {
    const question = 'Which units in Riyadh are vacant right now? '.repeat(6)
    await open(adminRunDetail({ question }))

    const call = adminCalls().at(-1)
    expect(call?.url.pathname).toBe('/api/admin/runs/run-30')
    expect(call?.headers).toEqual({ 'X-Admin-Token': TOKEN })
    const dialog = drawer() as HTMLElement
    expect(dialog.querySelector('blockquote')?.textContent?.trim()).toBe(question.trim())
  })

  it('shows the timeline of the models and tools with what each tool was asked and said', async () => {
    await open()

    const dialog = drawer() as HTMLElement
    const steps = [...dialog.querySelectorAll('.step')].map((step) => step.textContent?.replace(/\s+/g, ' ').trim())
    expect(steps[0]).toContain('Model call 1')
    expect(steps[0]).toContain('Claude Haiku 4.5')
    expect(steps[1]).toContain('search_units')
    expect(steps[1]).toContain('{"city":"Riyadh","status":"VACANT"}')
    expect(steps[2]).toContain('6 units found')
    expect(steps[3]).toContain('Model call 2')
    expect(dialog.textContent).toContain('3,000 read from the cache')
  })

  it('shows why the model was chosen, and the verdict with the values that could not be grounded', async () => {
    await open(adminRunDetail({
      model: SONNET,
      routedReason: 'GUARDRAIL_RETRY',
      guardrail: { status: 'RETRIED_FLAGGED', violations: 2 },
      guardrailFindings: [{ kind: 'NUMBER', value: '151' }, { kind: 'UNIT_CODE', value: 'A-999' }],
    }))

    const dialog = drawer() as HTMLElement
    expect(dialog.querySelector('.chip')?.textContent).toBe(en.trace.routed.GUARDRAIL_RETRY)
    expect(dialog.querySelector('.check')?.textContent).toContain(en.trace.checks.RETRIED_FLAGGED)
    const values = [...dialog.querySelectorAll('.values li')].map((item) => item.textContent?.replace(/\s+/g, ''))
    expect(values).toEqual([`${en.guardrail.kinds.NUMBER}151`.replace(/\s+/g, ''), `${en.guardrail.kinds.UNIT_CODE}A-999`.replace(/\s+/g, '')])
  })

  it('says there are no such values when the guardrail found none', async () => {
    await open()

    expect((drawer() as HTMLElement).textContent).toContain(en.admin.detail.noValues)
  })

  it('shows a model call that failed with the code it failed with', async () => {
    await open(adminRunDetail({
      routedReason: 'FALLBACK',
      llmCalls: [
        llmCall({ stopReason: null, outcome: 'ERROR', errorCode: 'llm_timeout', inputTokens: 0, outputTokens: 0 }),
        llmCall({ seq: 2, model: SONNET, stopReason: 'END_TURN' }),
      ],
      toolCalls: [],
      toolCallDetails: [],
    }))

    const dialog = drawer() as HTMLElement
    expect(dialog.textContent).toContain('Model call 1 failed')
    expect(dialog.textContent).toContain(en.chat.errors.llm_timeout)
  })

  it('shows what is known of the run while it loads, and says when it cannot be loaded', async () => {
    detailReply = () => ({ status: 500 })
    const wrapper = await render()
    await unlock(wrapper)

    await wrapper.get('li.row button').trigger('click')
    await flushPromises()

    const dialog = drawer() as HTMLElement
    expect(dialog.textContent).toContain('Which units in Riyadh are vacant right now?')
    expect(dialog.textContent).toContain(en.admin.detail.error)
  })

  it('goes back to the form when the server stops accepting the token', async () => {
    detailReply = () => ({ status: 403 })
    const wrapper = await render()
    await unlock(wrapper)

    await wrapper.get('li.row button').trigger('click')
    await flushPromises()

    expect(drawer()).toBeNull()
    expect(wrapper.find('input[type="password"]').exists()).toBe(true)
    expect(wrapper.get('[role="alert"]').text()).toBe(en.admin.rejected)
  })

  it('does not call a run that no longer exists a server without admin endpoints', async () => {
    detailReply = () => ({ status: 404 })
    const wrapper = await render()
    await unlock(wrapper)

    await wrapper.get('li.row button').trigger('click')
    await flushPromises()

    expect(wrapper.findAll('li.row')).toHaveLength(1)
    expect((drawer() as HTMLElement).textContent).toContain(en.admin.detail.error)
  })

  it('is written in Arabic when the page is', async () => {
    const wrapper = await open()

    i18n.global.locale.value = 'ar'
    await nextTick()

    const dialog = drawer() as HTMLElement
    expect(dialog.querySelector('h2')?.textContent).toBe(ar.admin.detail.title)
    expect(dialog.textContent).toContain(ar.trace.stats.tokensIn)
    wrapper.unmount()
  })
})
