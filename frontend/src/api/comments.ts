import { get, post, http, type ApiResponse } from './client'

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
  /** 目前恒为「访客」：游客不填昵称 */
  author: string
  content: string
  createdAt: string
}

/**
 * 发评论。
 *
 * `website` 是蜜罐字段：CSS 隐藏，正常用户看不见。传 true 时后端静默丢弃并回 200，
 * 所以前端拿到的结果和成功一样——这不是 bug，是故意的。
 */
export function createComment(slug: string, content: string, website = false) {
  return post<Comment | null>(`/blog/posts/${slug}/comments`, { content, website })
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
  createdAt: string
}

export function listAllComments(page = 0, size = 50) {
  return get<Page<AdminComment>>('/admin/comments', { page, size })
}

export function setCommentVisibility(id: number, status: 'VISIBLE' | 'HIDDEN') {
  return http.patch<ApiResponse<AdminComment>>(`/admin/comments/${id}/visibility`, { status }).then((res) => {
    if (res.data.code !== 0) throw new Error(res.data.message || '操作失败')
    return res.data.data
  })
}

export function deleteComment(id: number) {
  return http.delete<ApiResponse<void>>(`/admin/comments/${id}`).then((res) => {
    if (res.data.code !== 0) throw new Error(res.data.message || '删除失败')
    return res.data.data
  })
}

/** 公开搜索：只检索已发布笔记 */
export function publicSearch(question: string) {
  return post<{ results: import('./notes').SearchResult[]; total: number }>('/blog/search', { question })
}
