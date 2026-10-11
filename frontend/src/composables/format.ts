export function formatCost(usd: number): string {
  if (usd === 0) return '$0'
  return usd < 0.01 ? `$${usd.toFixed(5)}` : `$${usd.toFixed(3)}`
}

export function formatLatency(ms: number): string {
  return ms < 1000 ? `${ms} ms` : `${(ms / 1000).toFixed(1)} s`
}

/** "claude-haiku-4-5-20251001" becomes "Claude Haiku 4.5"; anything else is shown as is. */
export function formatModel(model: string): string {
  const match = /^claude-([a-z]+)-(\d+)-(\d+)/.exec(model)
  if (!match) return model
  const [, family, major, minor] = match
  return `Claude ${family.charAt(0).toUpperCase()}${family.slice(1)} ${major}.${minor}`
}

export function formatCount(n: number): string {
  return n.toLocaleString('en-US')
}

const money = new Intl.NumberFormat('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })

/** 3500 becomes "3,500.00": Latin digits in both languages, as everywhere in the app. */
export function formatMoney(amount: number): string {
  return money.format(amount)
}

/** Dates and times use Latin digits in Arabic too, so they read like the amounts next to them. */
const intlLocale = (locale: string) => (locale === 'ar' ? 'ar-u-nu-latn' : 'en')

/** "2026-03-01" becomes "Mar 2026". */
export function formatMonth(isoDate: string, locale: string): string {
  // The date has no time zone; reading it as UTC keeps the viewer's offset from moving it into the month before.
  return new Intl.DateTimeFormat(intlLocale(locale), { month: 'short', year: 'numeric', timeZone: 'UTC' }).format(
    new Date(isoDate),
  )
}

/** A point in time in the viewer's time zone, like "10 Oct 2026, 21:30". */
export function formatDateTime(isoInstant: string, locale: string): string {
  return new Intl.DateTimeFormat(intlLocale(locale), { dateStyle: 'medium', timeStyle: 'short' }).format(
    new Date(isoInstant),
  )
}

/** A calendar date such as a payment date: "2026-10-10" becomes "10 Oct 2026". */
export function formatDate(isoDate: string, locale: string): string {
  return new Intl.DateTimeFormat(intlLocale(locale), { dateStyle: 'medium', timeZone: 'UTC' }).format(
    new Date(isoDate),
  )
}

/** Milliseconds as "14:32". */
export function formatCountdown(ms: number): string {
  const seconds = Math.ceil(ms / 1000)
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`
}

/** The first `max` characters of `text`, with an ellipsis when something was left out; never half a character. */
export function truncate(text: string, max: number): string {
  const characters = Array.from(text)
  return characters.length <= max ? text : `${characters.slice(0, max).join('').trimEnd()}…`
}
