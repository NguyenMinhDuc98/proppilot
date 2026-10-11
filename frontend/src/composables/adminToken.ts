import { readonly, ref } from 'vue'

const STORAGE_KEY = 'proppilot.adminToken'

function remembered(): string {
  try {
    return sessionStorage.getItem(STORAGE_KEY) ?? ''
  } catch {
    return '' // storage unavailable (private mode): the token then lives in memory only
  }
}

const token = ref(remembered())

/**
 * The admin token of this browser tab: kept in memory, and in sessionStorage so that a reload does not ask for it
 * again, and gone when the tab closes. It goes into a request header and nowhere else: not a URL, not the console.
 */
export function useAdminToken() {
  function remember(value: string) {
    token.value = value
    try {
      sessionStorage.setItem(STORAGE_KEY, value)
    } catch {
      // ignore: the token just will not survive a reload
    }
  }

  function forget() {
    token.value = ''
    try {
      sessionStorage.removeItem(STORAGE_KEY)
    } catch {
      // ignore
    }
  }

  return { token: readonly(token), remember, forget }
}
