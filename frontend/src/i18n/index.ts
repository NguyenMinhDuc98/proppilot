import arElement from 'element-plus/es/locale/lang/ar'
import enElement from 'element-plus/es/locale/lang/en'
import { computed, watchEffect } from 'vue'
import { createI18n } from 'vue-i18n'
import ar from './ar'
import en from './en'

export type Locale = 'en' | 'ar'

const STORAGE_KEY = 'proppilot.locale'

function initialLocale(): Locale {
  try {
    const stored = localStorage.getItem(STORAGE_KEY)
    if (stored === 'en' || stored === 'ar') return stored
  } catch {
    // storage unavailable (private mode); fall through to the browser language
  }
  return navigator.language.toLowerCase().startsWith('ar') ? 'ar' : 'en'
}

export const i18n = createI18n({
  legacy: false,
  locale: initialLocale(),
  fallbackLocale: 'en',
  messages: { en, ar },
})

const locale = i18n.global.locale

/** Reactive locale state: direction, Element Plus locale and a toggle. Keeps <html lang dir> in sync. */
export function useLocale() {
  const dir = computed(() => (locale.value === 'ar' ? 'rtl' : 'ltr'))
  const elementLocale = computed(() => (locale.value === 'ar' ? arElement : enElement))

  function setLocale(next: Locale) {
    locale.value = next
    try {
      localStorage.setItem(STORAGE_KEY, next)
    } catch {
      // ignore: the choice just will not persist
    }
  }

  watchEffect(() => {
    document.documentElement.lang = locale.value
    document.documentElement.dir = dir.value
  })

  return {
    locale,
    dir,
    elementLocale,
    setLocale,
    toggle: () => setLocale(locale.value === 'ar' ? 'en' : 'ar'),
  }
}
