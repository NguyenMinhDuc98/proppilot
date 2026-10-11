import { getJson } from './http'
import type { AdminRun, AdminRunDetail, RunFilters } from './types'

/** How many runs one page of the admin list holds. */
export const ADMIN_PAGE_SIZE = 25

/** The one place a token is put in a request: a header, never a URL, which would end up in logs and history. */
const withToken = (token: string) => ({ 'X-Admin-Token': token })

/**
 * Runs newest first. `before` is the `createdAt` of the last run of the page before, to read the page that follows it.
 * Answers 404 when the server has no admin token configured and 403 for a token it does not accept.
 */
export const fetchAdminRuns = (token: string, filters: RunFilters, before?: string) =>
  getJson<AdminRun[]>(
    '/api/admin/runs',
    {
      limit: ADMIN_PAGE_SIZE,
      status: filters.status,
      model: filters.model,
      guardrailFlagged: filters.flaggedOnly ? 'true' : undefined,
      before,
    },
    withToken(token),
  )

export const fetchAdminRun = (token: string, runId: string) =>
  getJson<AdminRunDetail>(`/api/admin/runs/${encodeURIComponent(runId)}`, undefined, withToken(token))
