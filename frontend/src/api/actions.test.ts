import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  approveAction,
  fetchAction,
  fetchActions,
  fetchAudit,
  newIdempotencyKey,
  previewAction,
  rejectAction,
} from './actions'
import { ApiError } from './http'

const fetchMock = vi.fn()

function answer(body: unknown, init?: ResponseInit) {
  fetchMock.mockResolvedValueOnce(new Response(JSON.stringify(body), init))
}

/** The method, headers and parsed body of the n-th request. */
function request(n = 0) {
  const [url, init] = fetchMock.mock.calls[n] as [string, RequestInit | undefined]
  return {
    url,
    method: init?.method ?? 'GET',
    headers: (init?.headers ?? {}) as Record<string, string>,
    body: init?.body ? JSON.parse(init.body as string) : undefined,
  }
}

beforeEach(() => {
  fetchMock.mockReset()
  vi.stubGlobal('fetch', fetchMock)
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('reading', () => {
  it('lists the latest actions, one action and the audit trail of one action with plain GETs', async () => {
    answer([])
    answer({ id: 'a1' })
    answer([])

    await fetchActions()
    await fetchAction('a1')
    await fetchAudit('a1')

    expect(request(0)).toMatchObject({ url: '/api/actions', method: 'GET' })
    expect(request(1)).toMatchObject({ url: '/api/actions/a1', method: 'GET' })
    expect(request(2)).toMatchObject({ url: '/api/audit?actionId=a1', method: 'GET' })
  })
})

describe('previewAction', () => {
  it('posts the edit as JSON to the stateless preview endpoint', async () => {
    answer({ id: 'a1' })

    await previewAction('a1', { amount: 1500.5, overridePayment: false })

    expect(request()).toMatchObject({
      url: '/api/actions/a1/preview',
      method: 'POST',
      body: { amount: 1500.5, overridePayment: false },
    })
    expect(request().headers['Content-Type']).toBe('application/json')
  })
})

describe('approveAction', () => {
  it('sends the idempotency key as a header and the edit as the body', async () => {
    answer({ action: { id: 'a1' }, replayed: false })

    await approveAction('a1', 'key-1', { amount: 500, overridePayment: true })

    expect(request()).toMatchObject({
      url: '/api/actions/a1/approve',
      method: 'POST',
      body: { amount: 500, overridePayment: true },
    })
    expect(request().headers['Idempotency-Key']).toBe('key-1')
  })

  it('sends an empty object when nothing was edited', async () => {
    answer({ action: { id: 'a1' }, replayed: false })

    await approveAction('a1', 'key-1')

    expect(request().body).toEqual({})
  })

  it('keeps the key it is given, so a retry sends the same one', async () => {
    answer({ action: { id: 'a1' }, replayed: false })
    answer({ action: { id: 'a1' }, replayed: true })

    await approveAction('a1', 'key-1')
    await approveAction('a1', 'key-1')

    expect(request(0).headers['Idempotency-Key']).toBe('key-1')
    expect(request(1).headers['Idempotency-Key']).toBe('key-1')
  })

  it('turns an error answer into an ApiError that keeps the status and the code', async () => {
    answer({ code: 'action_expired', message: 'English text' }, { status: 410, statusText: 'Gone' })

    await expect(approveAction('a1', 'key-1')).rejects.toMatchObject({
      status: 410,
      code: 'action_expired',
    })
    answer({ code: 'rate_limited' }, { status: 429 })
    await expect(approveAction('a1', 'key-2')).rejects.toBeInstanceOf(ApiError)
  })
})

describe('rejectAction', () => {
  it('sends the reason when there is one and an empty object when there is not', async () => {
    answer({ id: 'a1' })
    answer({ id: 'a1' })

    await rejectAction('a1', 'Wrong tenant')
    await rejectAction('a1')

    expect(request(0)).toMatchObject({ url: '/api/actions/a1/reject', method: 'POST', body: { reason: 'Wrong tenant' } })
    expect(request(1).body).toEqual({})
  })
})

describe('newIdempotencyKey', () => {
  const accepted = /^[A-Za-z0-9_-]{1,64}$/

  it('makes a different key every time, in the form the server accepts', () => {
    const keys = new Set(Array.from({ length: 20 }, newIdempotencyKey))

    expect(keys.size).toBe(20)
    for (const key of keys) expect(key).toMatch(accepted)
  })

  it('still makes one where randomUUID is missing, as on a page served over plain http', () => {
    vi.stubGlobal('crypto', { getRandomValues: crypto.getRandomValues.bind(crypto) })

    const key = newIdempotencyKey()

    expect(key).toMatch(accepted)
    expect(newIdempotencyKey()).not.toBe(key)
  })
})
