import { del, get, patch, post } from './client'

/** 后端统一的分页体 */
export interface Page<T> {
  content: T[]
  totalElements: number
  totalPages: number
  number: number
  size: number
}

export interface Comment {
  id: number
  /** 「访客」或「博主」：博主回复时换称呼，公开页一眼能认出是谁在回 */
  author: string
  content: string
  createdAt: string
  /** 顶层评论为 null；回复指向父评论 */
  parentId?: number | null
  authorReply?: boolean
  /** 只挂在一层父评论上，回复本身不再带 replies */
  replies?: Comment[]
}

/**
 * 发评论。
 *
 * `website` 是蜜罐字段：CSS 隐藏，正常用户看不见。传 true 时后端静默丢弃并回 200，
 * 所以前端拿到的结果和成功一样——这不是 bug，是故意的。
 */
export function createComment(slug: string, content: string, website = false, parentId?: number) {
  return post<Comment | null>(`/blog/posts/${slug}/comments`, { content, website, parentId })
}

export function listComments(slug: string, page = 0, size = 20) {
  return get<Page<Comment>>(`/blog/posts/${slug}/comments`, { page, size })
}

/* ───────── 管理端 ───────── */

export interface AdminComment {
  id: number
  noteId: number
  noteTitle?: string
  noteSlug?: string
  author: string
  content: string
  status: 'VISIBLE' | 'HIDDEN'
  /** 只给管理员看：追溯「同一来源刷了一屏」的唯一线索 */
  clientIp?: string
  parentId?: number | null
  authorReply?: boolean
  createdAt: string
}

export function listAllComments(page = 0, size = 50) {
  return get<Page<AdminComment>>('/admin/comments', { page, size })
}

export function setCommentVisibility(id: number, status: 'VISIBLE' | 'HIDDEN') {
  return patch<AdminComment>(`/admin/comments/${id}/visibility`, { status }, '操作失败')
}

/** 以博主身份回复：回复本身也是一条评论，公开页上紧跟父评论 */
export function replyToComment(id: number, content: string) {
  return post<AdminComment>(`/admin/comments/${id}/reply`, { content }, '回复失败')
}

export function deleteComment(id: number) {
  return del<void>(`/admin/comments/${id}`, '删除失败')
}

/** 公开搜索：只检索已发布笔记 */
export function publicSearch(question: string) {
  return post<{ results: import('./notes').SearchResult[]; total: number }>('/blog/search', { question })
}
