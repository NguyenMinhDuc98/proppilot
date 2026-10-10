import { useI18n } from 'vue-i18n'
import { ApiError } from '../api/http'

/** Statuses without a more specific code get one sentence each; the key is the name under `actions.errorStatus`. */
const STATUS_KEYS: Record<number, string> = { 409: 'conflict', 410: 'gone', 422: 'invalid', 429: 'rateLimited' }

/** Words a failed action request in the viewer's language. Server text is never shown. */
export function useActionErrors() {
  const { t, te } = useI18n()

  /** @param code a stable code from the server, such as `amount_exceeds_outstanding` */
  function reason(code: string | undefined): string {
    return code && te(`actions.errors.${code}`) ? t(`actions.errors.${code}`) : t('actions.errors.generic')
  }

  function describe(error: unknown): string {
    if (!(error instanceof ApiError)) return t('actions.errors.network')
    if (error.code && te(`actions.errors.${error.code}`)) return t(`actions.errors.${error.code}`)
    const byStatus = STATUS_KEYS[error.status]
    return byStatus ? t(`actions.errorStatus.${byStatus}`) : t('actions.errors.generic')
  }

  return { describe, reason }
}
