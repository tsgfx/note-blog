import js from '@eslint/js'
import globals from 'globals'
import pluginVue from 'eslint-plugin-vue'
import tseslint from 'typescript-eslint'
import prettier from 'eslint-config-prettier/flat'

/**
 * 只查「是不是写错了」，不查「缩进几格」。
 * 排版规则全部交给 Prettier，所以这里刻意用 vue 的 flat/essential
 * 而不是 flat/recommended——后者含 max-attributes-per-line、html-indent
 * 这类格式规则，在 .vue 文件上会一次冒出几百条。
 */
export default [
  {
    ignores: [
      'dist/**',
      'node_modules/**',
      'public/**',
      'src/assets/**',
      '.*.mjs',
      // Tiptap v3 迁移试验封存的验证资产（gitignore 掉、不进 CI）。
      // 里面是打桩用的 `any`，不该污染日常 lint；等下次升级窗口启用时再一起收编
      'e2e-v3-park/**',
    ],
  },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  ...pluginVue.configs['flat/essential'],
  {
    files: ['**/*.vue'],
    languageOptions: { parserOptions: { parser: tseslint.parser } },
  },
  {
    files: ['**/*.{ts,vue}'],
    languageOptions: { globals: { ...globals.browser } },
    rules: {
      // App.vue 是根组件，Toaster 是被它直接挂载的全局提示宿主；
      // 重命名会牵动 main.ts / App.vue，收益不抵成本
      'vue/multi-word-component-names': ['error', { ignores: ['App', 'Toaster'] }],
      '@typescript-eslint/no-unused-vars': ['error', { argsIgnorePattern: '^_', caughtErrors: 'none' }],
    },
  },
  {
    files: ['*.config.{js,ts}', 'vite.config.ts'],
    languageOptions: { globals: { ...globals.node } },
  },
  prettier,
]
