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
      meta: { title: '首页', public: true },
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
})

export default router
