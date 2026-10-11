import { createRouter, createWebHistory } from 'vue-router'
import ChatView from './views/ChatView.vue'

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'chat', component: ChatView },
    { path: '/units', name: 'units', component: () => import('./views/UnitsView.vue') },
    { path: '/stats', name: 'stats', component: () => import('./views/StatsView.vue') },
    { path: '/activity', name: 'activity', component: () => import('./views/ActivityView.vue') },
    // Not in the navigation: the owner reaches it by its address, and the page asks for the admin token.
    { path: '/admin/traces', name: 'admin-traces', component: () => import('./views/AdminTracesView.vue') },
  ],
})
