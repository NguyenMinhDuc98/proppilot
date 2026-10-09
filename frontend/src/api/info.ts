import { getJson } from './http'
import type { AppInfo } from './types'

export const fetchInfo = () => getJson<AppInfo>('/api/info')
