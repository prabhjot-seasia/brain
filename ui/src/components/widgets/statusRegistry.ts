export type StatusColor = 'default' | 'primary' | 'secondary' | 'error' | 'info' | 'success' | 'warning'

const REGISTRY: Record<string, StatusColor> = {
  HIGH: 'error',
  CRITICAL: 'error',
  FAILED: 'error',
  ERROR: 'error',
  BLOCKED: 'error',

  MEDIUM: 'warning',
  PARTIAL: 'warning',
  CHANGES_REQUESTED: 'warning',
  WARN: 'warning',

  LOW: 'info',
  GENERATING: 'info',
  RUNNING: 'info',
  QUEUED: 'info',
  PENDING: 'info',
  PROPOSED: 'info',

  OK: 'success',
  COMPLETED: 'success',
  SUCCEEDED: 'success',
  PASS: 'success',
  APPROVED: 'success',

  INFORMATIONAL: 'success',
  CANCELLED: 'default',
  DEFAULT: 'default',
  UNKNOWN: 'default',
  '': 'default',
}

export function statusColor(status: string | null | undefined): StatusColor {
  if (!status) return 'default'
  const upper = status.toUpperCase()
  return REGISTRY[upper] ?? 'default'
}

export function registerStatus(status: string, color: StatusColor): void {
  REGISTRY[status.toUpperCase()] = color
}
