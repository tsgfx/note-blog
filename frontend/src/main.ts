import { createApp } from 'vue'
import { createPinia } from 'pinia'
import './style.css'
import App from './App.vue'
import router from './router'
import { installCodeCopy } from './lib/codecopy'
import { reportFatalError } from './lib/errorGuard'

installCodeCopy()

const app = createApp(App)

// 渲染/生命周期里抛错时给用户一条可见提示，而不是只留一块白屏（见 lib/errorGuard）
app.config.errorHandler = (error, _instance, info) => reportFatalError(error, info)
// 接口以外的异步错误（例如某个 await 链没人 catch）同样兜住
window.addEventListener('unhandledrejection', (event) => reportFatalError(event.reason, 'unhandledrejection'))

app.use(createPinia()).use(router).mount('#app')
