import { mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import App from './App.vue'
import { i18n } from './i18n'
import en from './i18n/en'

vi.mock('./composables/useAppInfo', () => ({ useAppInfo: () => ({ info: { value: null } }) }))

async function render() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: ['/', '/units', '/stats', '/activity'].map((path) => ({ path, component: { template: '<div />' } })),
  })
  await router.push('/activity')
  await router.isReady()
  return mount(App, { global: { plugins: [createPinia(), router, i18n] } })
}

describe('App navigation', () => {
  it('offers the Activity page in the sidebar and in the phone tab bar', async () => {
    const wrapper = await render()

    for (const selector of ['.sidebar .nav a', '.tabbar a']) {
      const links = wrapper.findAll(selector)
      expect(links.map((link) => link.attributes('href'))).toEqual(['/', '/units', '/stats', '/activity'])
      expect(links.at(-1)?.text()).toBe(en.nav.activity)
    }
  })

  it('marks the Activity page as the current one', async () => {
    const wrapper = await render()

    const current = wrapper.findAll('.tabbar a.router-link-exact-active')
    expect(current.map((link) => link.attributes('href'))).toEqual(['/activity'])
  })
})
