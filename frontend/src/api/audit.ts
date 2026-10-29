import { get } from './client'
import type { Page } from './notes'

export interface AuditLog {
  id: number
  username: string
  action: string
  target?: string
  detail?: string
  createdAt: string
}

export function listAuditLogs(page = 0, size = 50) {
  return get<Page<AuditLog>>('/admin/audit-logs', { page, size })
}
