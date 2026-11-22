/* 首屏渲染前确定主题，避免浅色闪烁。优先用户选择，其次跟随系统。
 *
 * 这段必须同步执行（放在 <head> 里、不带 defer/async），浏览器才会在绘制前跑完。
 * 早期版本是内联 <script>；为了能用严格的 CSP（script-src 'self'，不给
 * 'unsafe-inline'）把它挪成独立文件，代价是每页多一个约 400 字节的请求，
 * 所以 nginx 对它只给 no-cache（走 304），不给长缓存。 */
;(function () {
  var dark = false
  try {
    var stored = localStorage.getItem('notes_blog_theme')
    dark = stored ? stored === 'dark' : window.matchMedia('(prefers-color-scheme: dark)').matches
  } catch (e) {}
  document.documentElement.dataset.theme = dark ? 'dark' : 'light'
})()
