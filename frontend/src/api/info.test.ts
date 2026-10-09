import { afterEach, describe, expect, it, vi } from 'vitest'
import { fetchInfo } from './info'

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('fetchInfo', () => {
  it('reads the assistant mode from /api/info', async () => {
    const info = { mode: 'claude', provider: 'anthropic', model: 'claude-haiku-4-5-20251001' }
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify(info)))
    vi.stubGlobal('fetch', fetchMock)

    expect(await fetchInfo()).toEqual(info)
    expect(fetchMock).toHaveBeenCalledWith('/api/info')
  })

  it('rejects when the server answers with an error', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('{}', { status: 503, statusText: 'Unavailable' })))

    await expect(fetchInfo()).rejects.toMatchObject({ status: 503 })
  })
})
