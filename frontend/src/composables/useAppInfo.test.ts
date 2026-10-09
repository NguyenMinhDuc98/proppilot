import { flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { AppInfo } from '../api/types'

const fetchInfo = vi.fn()
vi.mock('../api/info', () => ({ fetchInfo: () => fetchInfo() }))

const offline: AppInfo = { mode: 'offline', provider: 'offline', model: 'rule-based', maxHistoryItems: 20 }

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

  describe('while the backend cannot be reached', () => {
    beforeEach(() => {
      vi.useFakeTimers()
    })

    afterEach(() => {
      vi.useRealTimers()
    })

    it('fails silently and retries by itself until the backend answers', async () => {
      fetchInfo.mockRejectedValueOnce(new Error('server asleep')).mockResolvedValue(offline)
      const useAppInfo = await freshUseAppInfo()

      const { info } = useAppInfo()
      await vi.advanceTimersByTimeAsync(0)
      expect(info.value).toBeNull()

      await vi.advanceTimersByTimeAsync(10_000)
      expect(fetchInfo).toHaveBeenCalledTimes(2)
      expect(info.value).toEqual(offline)
    })

    it('does not start another request while a retry is pending', async () => {
      fetchInfo.mockRejectedValue(new Error('server asleep'))
      const useAppInfo = await freshUseAppInfo()

      useAppInfo()
      await vi.advanceTimersByTimeAsync(0)
      useAppInfo()
      await vi.advanceTimersByTimeAsync(0)

      expect(fetchInfo).toHaveBeenCalledTimes(1)
    })

    it('stops retrying after a few attempts and tries again the next time it is used', async () => {
      fetchInfo.mockRejectedValue(new Error('server down'))
      const useAppInfo = await freshUseAppInfo()

      useAppInfo()
      await vi.advanceTimersByTimeAsync(5 * 60_000)
      const attempts = fetchInfo.mock.calls.length
      expect(attempts).toBeGreaterThan(1)

      await vi.advanceTimersByTimeAsync(5 * 60_000)
      expect(fetchInfo).toHaveBeenCalledTimes(attempts)

      fetchInfo.mockResolvedValue(offline)
      const { info } = useAppInfo()
      await vi.advanceTimersByTimeAsync(0)
      expect(info.value).toEqual(offline)
    })
  })
})
