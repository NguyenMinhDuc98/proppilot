import { computed, ref, watchEffect } from 'vue'

/** Milliseconds left until `expiresAt`, ticking once a second while `running`. */
export function useCountdown(expiresAt: () => string, running: () => boolean) {
  const now = ref(Date.now())

  watchEffect((onCleanup) => {
    if (!running()) return
    now.value = Date.now()
    const timer = setInterval(() => (now.value = Date.now()), 1000)
    onCleanup(() => clearInterval(timer))
  })

  const remainingMs = computed(() => Math.max(0, Date.parse(expiresAt()) - now.value))
  return { remainingMs }
}
