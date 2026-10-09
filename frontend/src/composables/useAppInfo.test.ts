import { flushPromises } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { AppInfo } from '../api/types'

const fetchInfo = vi.fn()
vi.mock('../api/info', () => ({ fetchInfo: () => fetchInfo() }))

const offline: AppInfo = { mode: 'offline', provider: 'offline', model: 'rule-based' }

// The composable keeps its state at module level, so each test loads a fresh copy.
async function freshUseAppInfo() {
  vi.resetModules()
  return (await import('./useAppInfo')).useAppInfo
}

describe('useAppInfo', () => {
  beforeEach(() => {
    fetchInfo.mockReset()
  })

  it('is empty until the info arrives', async () => {
    fetchInfo.mockResolvedValue(offline)
    const useAppInfo = await freshUseAppInfo()

    const { info } = useAppInfo()
    expect(info.value).toBeNull()

    await flushPromises()
    expect(info.value).toEqual(offline)
  })

  it('fetches once however many components ask', async () => {
    fetchInfo.mockResolvedValue(offline)
    const useAppInfo = await freshUseAppInfo()

    const first = useAppInfo()
    const second = useAppInfo()
    await flushPromises()
    const third = useAppInfo()

    expect(fetchInfo).toHaveBeenCalledTimes(1)
    expect(second.info.value).toEqual(offline)
    expect(third.info.value).toEqual(first.info.value)
  })

  it('fails silently and tries again the next time it is used', async () => {
    fetchInfo.mockRejectedValueOnce(new Error('server asleep')).mockResolvedValue(offline)
    const useAppInfo = await freshUseAppInfo()

    const { info } = useAppInfo()
    await flushPromises()
    expect(info.value).toBeNull()

    useAppInfo()
    await flushPromises()
    expect(fetchInfo).toHaveBeenCalledTimes(2)
    expect(info.value).toEqual(offline)
  })
})
