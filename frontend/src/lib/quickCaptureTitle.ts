/** 自动提取正文首行或时间戳作为闪念标题。 */
export function inferQuickCaptureTitle(rawContent: string, explicitTitle?: string, now = new Date()): string {
  if (explicitTitle && explicitTitle.trim()) {
    return explicitTitle.trim()
  }
  const lines = rawContent.split('\n')
  for (const line of lines) {
    const cleaned = line
      .replace(/^[#>\s*\-+]+/, '')
      .replace(/^\[[ xX]\]\s*/, '')
      .replace(/[`_~]/g, '')
      .trim()
    if (cleaned.length > 0) {
      return cleaned.length > 40 ? cleaned.slice(0, 40) + '…' : cleaned
    }
  }
  const pad = (n: number) => String(n).padStart(2, '0')
  return `闪念 ${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())} ${pad(now.getHours())}:${pad(now.getMinutes())}`
}
