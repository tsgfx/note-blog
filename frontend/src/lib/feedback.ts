import { reactive } from 'vue'

export type ToastKind = 'info' | 'success' | 'error'

export interface Toast {
  id: number
  message: string
  kind: ToastKind
}

export const toasts = reactive<Toast[]>([])

let seq = 0

export function notify(message: string, kind: ToastKind = 'info') {
  const id = ++seq
  toasts.push({ id, message, kind })
  window.setTimeout(() => dismiss(id), kind === 'error' ? 5000 : 2800)
}

export function dismiss(id: number) {
  const index = toasts.findIndex((t) => t.id === id)
  if (index >= 0) toasts.splice(index, 1)
}

export interface ConfirmOptions {
  title: string
  message?: string
  confirmText?: string
  cancelText?: string
  danger?: boolean
}

export interface ConfirmState extends ConfirmOptions {
  open: boolean
  resolve?: (value: boolean) => void
}

export const confirmState = reactive<ConfirmState>({
  open: false,
  title: '',
})

/** 打开确认框，用户选择后 resolve 为 true / false。 */
export function confirmAction(options: ConfirmOptions): Promise<boolean> {
  return new Promise((resolve) => {
    Object.assign(confirmState, { ...options, open: true, resolve })
  })
}

export function answerConfirm(value: boolean) {
  const resolve = confirmState.resolve
  confirmState.open = false
  confirmState.resolve = undefined
  resolve?.(value)
}
