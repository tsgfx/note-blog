import { createApp } from 'vue'
import { createPinia } from 'pinia'
// KaTeX 样式要在自己的 style.css 之前，.prose 里的覆盖规则才压得住它
import 'katex/dist/katex.min.css'
import './style.css'
import App from './App.vue'
import router from './router'
import { installCodeCopy } from './lib/codecopy'

installCodeCopy()
createApp(App).use(createPinia()).use(router).mount('#app')
