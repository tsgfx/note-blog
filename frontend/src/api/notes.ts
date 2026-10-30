import { get, post, http, type ApiResponse } from './client'

export interface Page<T> {
  content: T[]
  totalElements: number
  totalPages: number
  number: number
  size: number
}

export interface NoteSummary {
  id: number
  title: string
  slug: string
  summary?: string
  status: 'DRAFT' | 'PUBLISHED'
  sourcePath?: string
  publishedAt?: string
  /** 已发布但改过还没重新发布：线上仍是旧内容 */
  hasUnpublishedEdits?: boolean
  updatedAt?: string
  tags: string[]
  viewCount?: number
}

export interface AdjacentPost {
  title: string
  slug: string
}

export interface NoteDetail extends NoteSummary {
  contentMd: string
  sourcePath?: string
  createdAt?: string
  prev?: AdjacentPost | null
  next?: AdjacentPost | null
}

export interface SearchResult {
  noteId: number
  title: string
  slug: string
  heading?: string
  content: string
  score: number
}

export function health() {
  return get<{ status: string; time: string }>('/health')
}

/** 相关笔记推荐（复用分片向量，不需要再调 embedding）。 */
export interface RelatedNote {
  id: number
  title: string
  slug: string
  summary?: string
  sourcePath?: string
  /** 相似度 0~1，只用于排序展示，不影响功能 */
  score: number
}

export function getRelatedNotes(id: number, limit = 5) {
  return get<RelatedNote[]>(`/notes/${id}/related`, { limit })
}

/** 公开文章的相关推荐（仅推荐已发布文章）。 */
export function getPublishedRelatedNotes(slug: string, limit = 4) {
  return get<RelatedNote[]>(`/blog/posts/${slug}/related`, { limit })
}

/** 公开博客的热门标签（只统计已发布文章，按篇数降序）。 */
export function listPublishedTags(limit = 12) {
  return get<TagCount[]>('/blog/tags', { limit })
}

/** 发布前预检：BLOCK = 发不出去，WARN = 发得出去但有风险。 */
export interface PreflightFinding {
  level: 'BLOCK' | 'WARN'
  code: string
  message: string
}

export interface PreflightItem {
  id: number
  title: string
  findings: PreflightFinding[]
}

export interface PreflightReport {
  items: PreflightItem[]
  blockers: number
  warnings: number
}

export function preflightNotes(ids: number[]) {
  return post<PreflightReport>('/notes/preflight', { ids })
}

export function listNotes(
  params: {
    status?: string
    /** 目录路径：第一段就是「库」，多段就是子目录 */
    path?: string
    /** 旧名字，只支持第一段，保留是为了旧书签不断 */
    library?: string
    q?: string
    tag?: string[]
    page?: number
    size?: number
  } = {},
) {
  return get<Page<NoteSummary>>('/notes', params)
}

/** 左侧「库」导航：库名与篇数。 */
export interface LibraryCount {
  name: string
  count: number
}

export function listLibraries() {
  return get<LibraryCount[]>('/notes/libraries')
}

/** 左侧「目录」导航：多级目录树，每层带篇数（含整棵子树）。 */
export interface FolderNode {
  name: string
  /** 完整路径，点选时回传给后端做前缀筛选 */
  path: string
  count: number
  children: FolderNode[]
}

export function listFolders(params: { status?: string } = {}) {
  return get<FolderNode[]>('/notes/folders', params)
}

/** 左侧「分类」导航：标签与篇数。传 path 时只统计该目录里的标签。 */
export interface TagCount {
  name: string
  count: number
}

export function listTags(params: { status?: string; path?: string; library?: string } = {}) {
  return get<TagCount[]>('/notes/tags', params)
}

export function getNote(slug: string) {
  return get<NoteDetail>(`/notes/${slug}`)
}

export function getPublishedNote(slug: string) {
  return get<NoteDetail>(`/blog/posts/${slug}`)
}

export function listPublishedNotes(
  params:
    | { page?: number; size?: number; tag?: string; keyword?: string; sort?: 'recent' | 'views' }
    | number = 0,
  size = 20,
) {
  if (typeof params === 'number') {
    return get<Page<NoteSummary>>('/blog/posts', { page: params, size })
  }
  return get<Page<NoteSummary>>('/blog/posts', params)
}

/** 记录文章浏览量（PV），独立端点避免破坏 ETag 缓存 */
export function recordPostView(slug: string) {
  return post<{ viewCount: number }>(`/blog/posts/${slug}/view`)
}

/** 首页统计。后端只返回已发布相关的聚合数字（不含笔记总数，防止暴露草稿量）。 */
export interface BlogStats {
  published: number
  tags: number
  latestPublished?: string
}

export function getBlogStats() {
  return get<BlogStats>('/blog/stats')
}

export function searchNotes(question: string, limit = 8) {
  return post<SearchResult[]>('/search', { question, limit })
}

export interface NoteInput {
  title: string
  summary?: string
  contentMd: string
  tags: string[]
}

export function createNote(input: NoteInput) {
  return post<NoteDetail>('/notes', input)
}

export async function updateNote(id: number, input: Partial<NoteInput>) {
  const res = await http.patch<ApiResponse<NoteDetail>>(`/notes/${id}`, input)
  if (res.data.code !== 0) throw new Error(res.data.message || '保存失败')
  return res.data.data
}

export async function deleteNote(id: number) {
  const res = await http.delete<ApiResponse<void>>(`/notes/${id}`)
  if (res.data.code !== 0) throw new Error(res.data.message || '删除失败')
}

export async function uploadImage(file: File) {
  const form = new FormData()
  form.append('file', file)
  const res = await http.post<ApiResponse<{ url: string }>>('/assets', form)
  if (res.data.code !== 0) throw new Error(res.data.message || '图片上传失败')
  return res.data.data.url
}

export async function publishNote(id: number) {
  const res = await http.post<ApiResponse<NoteDetail>>(`/notes/${id}/publish`)
  if (res.data.code !== 0) throw new Error(res.data.message || '发布失败')
  return res.data.data
}

export async function unpublishNote(id: number) {
  const res = await http.post<ApiResponse<NoteDetail>>(`/notes/${id}/unpublish`)
  if (res.data.code !== 0) throw new Error(res.data.message || '下线失败')
  return res.data.data
}

/**
 * 自动生成摘要与标签。默认只填空白字段；force=true 连已有内容一起重写。
 * 服务端要等 LLM 出结果，可能花几秒，调用方自己挂 loading。
 */
export async function generateNoteMeta(id: number, force = false) {
  const res = await http.post<ApiResponse<NoteDetail>>(`/notes/${id}/generate-meta${force ? '?force=true' : ''}`)
  if (res.data.code !== 0) throw new Error(res.data.message || '生成失败')
  return res.data.data
}

/** 单条被跳过的原因，title 可能为空（笔记已不存在时） */
export interface BatchSkippedItem {
  id: number
  title?: string
  reason: string
}

export interface BatchResult {
  total: number
  succeeded: number
  skipped: number
  skippedItems: BatchSkippedItem[]
}

export function publishNotes(ids: number[]) {
  return post<BatchResult>('/notes/batch/publish', { ids })
}

export function unpublishNotes(ids: number[]) {
  return post<BatchResult>('/notes/batch/unpublish', { ids })
}

/** 批量补元数据的结果。四个计数分开是为了区分「花了额度」与「本来就有」 */
export interface MetaBatchReport {
  requested: number
  generated: number
  skipped: number
  failed: number
  hasMore: boolean
  remaining: number
  failures: { id: number; title?: string; reason: string }[]
}

export interface GenerateMetaBatchPayload {
  /** 为空表示「库里缺什么补什么」，传了就是勾选的那批 */
  ids?: number[]
  force?: boolean
  /** 单批上限，后端最多 10 篇 */
  limit?: number
}

export function generateMetaBatch(payload: GenerateMetaBatchPayload = {}) {
  return post<MetaBatchReport>('/notes/generate-meta/batch', payload)
}

/**
 * 补建缺失的语义索引（有正文但没有向量块的笔记）。
 *
 * 返回这一轮排队的篇数，以及全库仍缺多少——线程池队列只有 20 个槽位，
 * 整库缺分片（比如刚恢复过备份）必须反复调用，`remaining` 是收敛依据。
 */
export function reindexMissingNotes() {
  return post<{ queued: number; remaining: number }>('/notes/reindex')
}
