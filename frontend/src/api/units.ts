import { getJson } from './http'
import type { Stats, UnitPage, UnitQuery } from './types'

export const fetchUnits = (query: UnitQuery) =>
  getJson<UnitPage>('/api/units', { ...query })

export const fetchStats = () => getJson<Stats>('/api/stats')
