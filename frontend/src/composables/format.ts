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
