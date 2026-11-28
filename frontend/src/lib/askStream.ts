export interface AskStreamEvent {
  type: string
  data: Record<string, unknown>
}

/** 解析一个 SSE block；只消费 data 行，非 data 行和坏 JSON 都按无事件处理。 */
export function parseAskStreamEvent(block: string): AskStreamEvent | null {
  const line = block.split('\n').find((row) => row.startsWith('data:'))
  if (!line) return null
  try {
    const payload = JSON.parse(line.slice(5).trim()) as AskStreamEvent
    return { type: payload.type, data: payload.data ?? {} }
  } catch {
    return null
  }
}
