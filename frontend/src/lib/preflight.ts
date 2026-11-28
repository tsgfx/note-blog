import type { PreflightItem, PreflightReport } from '../api/notes'

/** 发布前检查的纯决策规则：UI 只负责呈现，不重新发明这些判断。 */
export function hasBlockingFinding(item: PreflightItem): boolean {
  return item.findings.some((finding) => finding.level === 'BLOCK')
}

export function warningPreflightIds(report: PreflightReport): number[] {
  return report.items.filter((item) => item.findings.some((finding) => finding.level === 'WARN')).map((item) => item.id)
}

export function publishablePreflightIds(report: PreflightReport, excludedIds: ReadonlySet<number>): number[] {
  return report.items.filter((item) => !hasBlockingFinding(item) && !excludedIds.has(item.id)).map((item) => item.id)
}
