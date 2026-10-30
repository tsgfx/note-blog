import { get } from './client'

/** 备份状态。后端只读目录里的文件时间与大小，不解析备份内容。 */
export interface BackupStatus {
  /** 备份目录是否可读（容器里是 ./backups 的只读挂载） */
  available: boolean
  dir: string
  lastSuccessAt?: string
  lastSuccessSize: number
  copies: number
  lastErrorAt?: string
  lastErrorMessage?: string
  /** 已经超过阈值没成功备份 —— 页面该亮警告的就是这一条 */
  stale: boolean
}

export function getBackupStatus() {
  return get<BackupStatus>('/system/backup')
}

/** 依赖健康状态。`/api/health` 是公开接口，只报依赖，不含任何业务数据。 */
export interface HealthReport {
  /** up = 依赖全通；degraded = 有依赖不可用（HTTP 仍是 200） */
  status: 'up' | 'degraded'
  components: Record<string, boolean>
  /** 部署版本（构建时的短 commit）；本地开发是 dev */
  version?: string
  time: string
}

export function getHealth() {
  return get<HealthReport>('/health')
}

export interface BrokenAssetItem {
  noteId: number
  noteTitle: string
  noteSlug?: string
  assetPath: string
}

export interface IntegrityReport {
  totalNotes: number
  publishedNotes: number
  draftNotes: number
  missingChunksNotes: number
  emptyNotes: number
  brokenAssets: BrokenAssetItem[]
}

export function getIntegrityReport() {
  return get<IntegrityReport>('/system/integrity')
}
