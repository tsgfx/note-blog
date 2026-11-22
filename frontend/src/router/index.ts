import { createRouter, createWebHistory } from 'vue-router'

declare module 'vue-router' {
  interface RouteMeta {
    /** 公开页面：未登录也可访问。其余页面默认需要登录。 */
    public?: boolean
    title?: string
  }
}

const router = createRouter({
  history: createWebHistory(),
  /**
   * 之前没有这个配置，从翻到第 3 页的列表点进文章会停在原来的滚动位置，
   * 看着像"打不开"。前进后退保留位置，其余一律回到顶部。
   */
  scrollBehavior(to, _from, savedPosition) {
    if (savedPosition) return savedPosition
    if (to.hash) return { el: to.hash, top: 80 }
    return { top: 0 }
  },
  routes: [
    {
      path: '/',
      name: 'home',
      component: () => import('../views/HomeView.vue'),
      meta: { title: '', public: true },
    },
    {
      path: '/ask',
      name: 'ask',
      redirect: { path: '/notes', query: { ask: '1' } },
      meta: { title: '问答' },
    },
    { path: '/notes', name: 'notes', component: () => import('../views/NotesView.vue'), meta: { title: '笔记' } },
    {
      path: '/notes/:slug',
      name: 'note-detail',
      component: () => import('../views/NoteDetailView.vue'),
      meta: { title: '笔记' },
    },
    {
      path: '/editor/new',
      name: 'editor-new',
      component: () => import('../views/NoteEditorView.vue'),
      meta: { title: '新建笔记' },
    },
    {
      path: '/editor/:slug',
      name: 'editor',
      component: () => import('../views/NoteEditorView.vue'),
      meta: { title: '编辑笔记' },
    },
    {
      path: '/blog',
      name: 'blog',
      component: () => import('../views/BlogView.vue'),
      meta: { title: '博客', public: true },
    },
    {
      path: '/blog/:slug',
      name: 'blog-detail',
      component: () => import('../views/NoteDetailView.vue'),
      meta: { title: '文章', public: true },
    },
    {
      path: '/admin/audit',
      name: 'audit',
      component: () => import('../views/AuditLogView.vue'),
      meta: { title: '操作日志' },
    },
    {
      path: '/admin/comments',
      name: 'comments-admin',
      component: () => import('../views/CommentsView.vue'),
      meta: { title: '评论管理' },
    },
    {
      path: '/settings',
      name: 'settings',
      component: () => import('../views/SettingsView.vue'),
      meta: { title: '设置' },
    },
    {
      path: '/login',
      name: 'login',
      component: () => import('../views/LoginView.vue'),
      meta: { title: '登录', public: true },
    },
    {
      // 必须是 public：未登录的人访问错地址，不该先被弹去登录页
      path: '/:pathMatch(.*)*',
      name: 'not-found',
      component: () => import('../views/NotFoundView.vue'),
      meta: { title: '页面不存在', public: true },
    },
  ],
})

/**
 * 发版后的旧 chunk 失效。
 *
 * <p>打包产物是带 hash 的文件，nginx 给 {@code /assets/} 一年长缓存；而部署会整体替换镜像，
 * 旧 hash 文件随之消失。停在旧页面上的标签页点下一个懒加载路由时，动态 import 拿到 404，
 * 导航直接失败、页面停在原地（看起来像"点了没反应"，控制台里只有一行红字）。
 *
 * <p>处理方式：刷新一次拿新的 {@code index.html}，然后回到目标地址。
 * 用 sessionStorage 记时间戳，一分钟内只刷一次——真断网时每个路由都失败，
 * 不节流就会变成无限刷新。
 */
const CHUNK_RELOAD_KEY = 'notes_blog_chunk_reload_at'

router.onError((error, to) => {
  const message = String((error as Error | undefined)?.message ?? error)
  const chunkFailure = /dynamically imported module|Importing a module script failed|Unable to preload CSS/i
  if (!chunkFailure.test(message)) return

  const last = Number(sessionStorage.getItem(CHUNK_RELOAD_KEY) ?? 0)
  if (Date.now() - last < 60_000) return
  sessionStorage.setItem(CHUNK_RELOAD_KEY, String(Date.now()))
  window.location.assign(to.fullPath)
})

router.beforeEach((to) => {
  const loggedIn = Boolean(localStorage.getItem('notes_blog_token'))
  if (!to.meta.public && !loggedIn) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  if (to.name === 'login' && loggedIn) {
    return { name: 'notes' }
  }
})

router.afterEach((to) => {
  document.title = to.meta.title ? `${to.meta.title} · 一隅` : '一隅'
  // 导航成功说明 chunk 是最新的，把"已自动刷新过"的标记清掉，下次发版还能再救一次
  sessionStorage.removeItem(CHUNK_RELOAD_KEY)
})

export default router
