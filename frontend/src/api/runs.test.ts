import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { RunTrace } from './types'

const fetchMock = vi.fn()

const trace = { runId: 'a1b2', status: 'OK', llmCalls: [], toolCalls: [] } as unknown as RunTrace

/** A module of its own for each test, because what was read is kept by the module. */
async function load() {
  vi.resetModules()
  return (await import('./runs')).fetchRunTrace
}

beforeEach(() => {
  fetchMock.mockReset()
  vi.stubGlobal('fetch', fetchMock)
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('fetchRunTrace', () => {
  it('reads the stored trace of a run from its own address', async () => {
    fetchMock.mockResolvedValueOnce(new Response(JSON.stringify(trace)))
    const fetchRunTrace = await load()

    expect(await fetchRunTrace('a1b2')).toEqual(trace)
    expect(fetchMock.mock.calls[0][0]).toBe('/api/runs/a1b2/trace')
  })

  it('asks once for a run, however often it is opened', async () => {
    fetchMock.mockImplementation(() => Promise.resolve(new Response(JSON.stringify(trace))))
    const fetchRunTrace = await load()

    await fetchRunTrace('a1b2')
    await fetchRunTrace('a1b2')
    await fetchRunTrace('c3d4')

    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it('asks again after a failure', async () => {
    fetchMock
      .mockResolvedValueOnce(new Response('{}', { status: 503, statusText: 'Unavailable' }))
      .mockResolvedValueOnce(new Response(JSON.stringify(trace)))
    const fetchRunTrace = await load()

    await expect(fetchRunTrace('a1b2')).rejects.toMatchObject({ status: 503 })
    expect(await fetchRunTrace('a1b2')).toEqual(trace)
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it('rejects with the status of a run the server does not know', async () => {
    fetchMock.mockResolvedValueOnce(new Response('', { status: 404, statusText: 'Not Found' }))
    const fetchRunTrace = await load()

    await expect(fetchRunTrace('a1b2')).rejects.toMatchObject({ status: 404 })
  })
})
