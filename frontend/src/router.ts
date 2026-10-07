import { createRouter, createWebHistory } from 'vue-router'
import ChatView from './views/ChatView.vue'

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'chat', component: ChatView },
    { path: '/units', name: 'units', component: () => import('./views/UnitsView.vue') },
    { path: '/stats', name: 'stats', component: () => import('./views/StatsView.vue') },
  ],
})
