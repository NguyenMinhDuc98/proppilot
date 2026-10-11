const BASE = (import.meta.env.VITE_API_URL ?? '').replace(/\/$/, '')

export class ApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
    /** The stable error code the actions API sends, for example `action_expired`; the UI words it itself. */
    readonly code?: string,
  ) {
    super(message)
  }
}

export function apiUrl(path: string): string {
  return `${BASE}${path}`
}

/** Turns a failed response into an ApiError carrying the server's `detail` message and error `code` when it has them. */
export async function failure(response: Response): Promise<ApiError> {
  let detail = response.statusText
  let code: string | undefined
  try {
    const body = await response.json()
    if (typeof body?.detail === 'string') detail = body.detail
    if (typeof body?.code === 'string') code = body.code
  } catch {
    // body was not JSON; keep the status text
  }
  return new ApiError(response.status, detail, code)
}

export async function getJson<T>(
  path: string,
  params?: Record<string, string | number | undefined>,
  headers?: Record<string, string>,
): Promise<T> {
  const query = new URLSearchParams()
  Object.entries(params ?? {}).forEach(([key, value]) => {
    if (value !== undefined && value !== '') query.set(key, String(value))
  })
  const suffix = query.size ? `?${query}` : ''
  const response = await fetch(apiUrl(path) + suffix, { headers })
  if (!response.ok) throw await failure(response)
  return response.json() as Promise<T>
}

export async function postJson<T>(path: string, body: unknown, headers: Record<string, string> = {}): Promise<T> {
  const response = await fetch(apiUrl(path), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...headers },
    body: JSON.stringify(body),
  })
  if (!response.ok) throw await failure(response)
  return response.json() as Promise<T>
}
