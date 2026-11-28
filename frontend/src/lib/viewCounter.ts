/** 公开文章阅读量本地去重。键名与“每会话每篇只计一次”的规则只能由这里拥有。 */

export function claimPostView(slug: string): boolean {
  if (!slug) return false
  const key = `blog_viewed_${slug}`
  try {
    if (sessionStorage.getItem(key)) return false
    sessionStorage.setItem(key, '1')
    return true
  } catch {
    // 私密模式或禁用 storage 时无法可靠去重；沿用历史行为，仍允许这次上报尝试
    return true
  }
}
