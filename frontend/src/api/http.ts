const BASE = (import.meta.env.VITE_API_URL ?? '').replace(/\/$/, '')

export class ApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message)
  }
}

export function apiUrl(path: string): string {
  return `${BASE}${path}`
}

/** Turns a failed response into an ApiError carrying the server's `detail` message when there is one. */
export async function failure(response: Response): Promise<ApiError> {
  let detail = response.statusText
  try {
    const body = await response.json()
    if (typeof body?.detail === 'string') detail = body.detail
  } catch {
    // body was not JSON; keep the status text
  }
  return new ApiError(response.status, detail)
}

export async function getJson<T>(path: string, params?: Record<string, string | number | undefined>): Promise<T> {
  const query = new URLSearchParams()
  Object.entries(params ?? {}).forEach(([key, value]) => {
    if (value !== undefined && value !== '') query.set(key, String(value))
  })
  const suffix = query.size ? `?${query}` : ''
  const response = await fetch(apiUrl(path) + suffix)
  if (!response.ok) throw await failure(response)
  return response.json() as Promise<T>
}
