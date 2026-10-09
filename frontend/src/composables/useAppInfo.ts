import { readonly, ref } from 'vue'
import { fetchInfo } from '../api/info'
import type { AppInfo } from '../api/types'

const info = ref<AppInfo | null>(null)
let loading = false

async function load() {
  loading = true
  try {
    info.value = await fetchInfo()
  } catch {
    // purely informational: without it the badge stays hidden and the next use tries again
  } finally {
    loading = false
  }
}

/** Which assistant the backend runs. Fetched once for the whole app, however many components ask; null until it arrives. */
export function useAppInfo() {
  if (!info.value && !loading) void load()
  return { info: readonly(info) }
}
