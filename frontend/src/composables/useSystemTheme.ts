/** Mirrors the OS light/dark preference onto <html class="dark"> so Element Plus switches theme with our own CSS. */
export function useSystemTheme() {
  const query = window.matchMedia('(prefers-color-scheme: dark)')
  const apply = () => document.documentElement.classList.toggle('dark', query.matches)
  apply()
  query.addEventListener('change', apply)
}
