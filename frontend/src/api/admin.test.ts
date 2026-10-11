import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ADMIN_PAGE_SIZE, fetchAdminRun, fetchAdminRuns } from './admin'

const fetchMock = vi.fn()
const TOKEN = 'a-long-admin-token-0123456789'

const sentUrl = () => new URL(fetchMock.mock.calls[0][0] as string, 'http://localhost')
const sentHeaders = () => (fetchMock.mock.calls[0][1] as RequestInit).headers as Record<string, string>

beforeEach(() => {
  fetchMock.mockReset()
  fetchMock.mockImplementation(() => Promise.resolve(new Response('[]')))
  vi.stubGlobal('fetch', fetchMock)
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('fetchAdminRuns', () => {
  it('sends the token in a header and never in the address', async () => {
    await fetchAdminRuns(TOKEN, {})

    expect(sentHeaders()).toEqual({ 'X-Admin-Token': TOKEN })
    expect(fetchMock.mock.calls[0][0]).not.toContain(TOKEN)
    expect(sentUrl().pathname).toBe('/api/admin/runs')
  })

  it('asks for one page of runs and no filter when none is set', async () => {
    await fetchAdminRuns(TOKEN, {})

    expect(Object.fromEntries(sentUrl().searchParams)).toEqual({ limit: String(ADMIN_PAGE_SIZE) })
  })

  it('passes the status, the model, the flagged filter and the cursor of the page before', async () => {
    await fetchAdminRuns(TOKEN, { status: 'ERROR', model: 'claude-haiku-4-5-20251001', flaggedOnly: true }, '2026-10-11T08:00:00.123456Z')

    expect(Object.fromEntries(sentUrl().searchParams)).toEqual({
      limit: String(ADMIN_PAGE_SIZE),
      status: 'ERROR',
      model: 'claude-haiku-4-5-20251001',
      guardrailFlagged: 'true',
      before: '2026-10-11T08:00:00.123456Z',
    })
  })

  it('leaves the flagged filter out when it is not asked for', async () => {
    await fetchAdminRuns(TOKEN, { flaggedOnly: false })

    expect(sentUrl().searchParams.has('guardrailFlagged')).toBe(false)
  })

  it.each([404, 403])('rejects with the status %i the server answered', async (status) => {
    fetchMock.mockResolvedValueOnce(new Response('', { status, statusText: 'No' }))

    await expect(fetchAdminRuns(TOKEN, {})).rejects.toMatchObject({ status })
  })
})

describe('fetchAdminRun', () => {
  it('reads one run with the token in a header', async () => {
    fetchMock.mockResolvedValueOnce(new Response('{}'))

    await fetchAdminRun(TOKEN, 'a1b2')

    expect(sentUrl().pathname).toBe('/api/admin/runs/a1b2')
    expect(sentUrl().search).toBe('')
    expect(sentHeaders()).toEqual({ 'X-Admin-Token': TOKEN })
  })
})
