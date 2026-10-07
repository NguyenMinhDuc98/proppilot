import 'element-plus/dist/index.css'
import 'element-plus/theme-chalk/dark/css-vars.css'
import { createPinia } from 'pinia'
import { createApp } from 'vue'
import App from './App.vue'
import { i18n } from './i18n'
import { useSystemTheme } from './composables/useSystemTheme'
import { router } from './router'
import './styles.css'

useSystemTheme()

createApp(App).use(createPinia()).use(router).use(i18n).mount('#app')
