import { readonly, ref } from 'vue'
import { fetchInfo } from '../api/info'
import type { AppInfo } from '../api/types'

// The first request can land while the backend is still waking from idle, which takes up to about a minute.
const RETRY_DELAYS_MS = [2_000, 5_000, 15_000, 30_000, 60_000]

const info = ref<AppInfo | null>(null)
let loading = false

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms))

async function load() {
  loading = true
  for (let attempt = 0; !info.value && attempt <= RETRY_DELAYS_MS.length; attempt++) {
    if (attempt > 0) await sleep(RETRY_DELAYS_MS[attempt - 1])
    try {
      info.value = await fetchInfo()
    } catch {
      // purely informational: the badge stays hidden until a retry, or the next use after the last one, succeeds
    }
  }
  loading = false
}

/** Which assistant the backend runs. Fetched once for the whole app, however many components ask; null until it arrives. */
export function useAppInfo() {
  if (!info.value && !loading) void load()
  return { info: readonly(info) }
}
