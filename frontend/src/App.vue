<script setup lang="ts">
import { ElConfigProvider } from 'element-plus'
import { useI18n } from 'vue-i18n'
import { useLocale } from './i18n'

const { t } = useI18n()
const { locale, elementLocale, setLocale, toggle } = useLocale()

const links = [
  { to: '/', key: 'chat', icon: 'M21 15a2 2 0 0 1-2 2H8l-5 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z' },
  { to: '/units', key: 'units', icon: 'M5 3h14v18H5zM9 8h.01M15 8h.01M9 12h.01M15 12h.01M10 21v-4h4v4' },
  { to: '/stats', key: 'stats', icon: 'M4 20V10M10 20V4M16 20v-7M22 20H2' },
] as const
</script>

<template>
  <ElConfigProvider :locale="elementLocale">
    <div class="shell">
      <aside class="sidebar">
        <RouterLink to="/" class="brand">
          <svg viewBox="0 0 32 32" width="30" height="30" aria-hidden="true">
            <rect width="32" height="32" rx="9" fill="var(--pp-primary)" />
            <path d="M8 17 16 9l8 8v7h-5v-5h-6v5H8z" fill="#fff" />
          </svg>
          <span>{{ t('app.title') }}</span>
        </RouterLink>

        <nav :aria-label="t('app.navLabel')" class="nav">
          <RouterLink v-for="link in links" :key="link.key" :to="link.to" class="nav-link">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path :d="link.icon" /></svg>
            {{ t(`nav.${link.key}`) }}
          </RouterLink>
        </nav>

        <div class="foot">
          <div class="segmented" role="group" aria-label="Language">
            <button type="button" :aria-pressed="locale === 'en'" @click="setLocale('en')">English</button>
            <button type="button" :aria-pressed="locale === 'ar'" @click="setLocale('ar')">العربية</button>
          </div>
          <p>{{ t('app.demoNote') }}</p>
        </div>
      </aside>

      <div class="content">
        <header class="topbar">
          <RouterLink to="/" class="brand">
            <svg viewBox="0 0 32 32" width="28" height="28" aria-hidden="true">
              <rect width="32" height="32" rx="8" fill="var(--pp-primary)" />
              <path d="M8 17 16 9l8 8v7h-5v-5h-6v5H8z" fill="#fff" />
            </svg>
            <span>{{ t('app.title') }}</span>
          </RouterLink>
          <button type="button" class="lang" @click="toggle">{{ t('app.language') }}</button>
        </header>

        <main><RouterView /></main>

        <nav :aria-label="t('app.navLabel')" class="tabbar">
          <RouterLink v-for="link in links" :key="link.key" :to="link.to" class="tab">
            <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path :d="link.icon" /></svg>
            {{ t(`nav.${link.key}`) }}
          </RouterLink>
        </nav>
      </div>
    </div>
  </ElConfigProvider>
</template>

<style scoped>
.shell { display: grid; grid-template-columns: 248px minmax(0, 1fr); min-height: 100vh; }
.sidebar {
  position: sticky;
  top: 0;
  height: 100vh;
  display: flex;
  flex-direction: column;
  gap: 28px;
  padding: 20px 16px;
  background: var(--pp-surface);
  border-inline-end: 1px solid var(--pp-border);
}
.brand { display: flex; align-items: center; gap: 10px; padding: 0 8px; color: var(--pp-text); font-size: 18px; font-weight: 700; text-decoration: none; }
.nav { display: flex; flex-direction: column; gap: 4px; }
.nav-link {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 12px;
  border-radius: 10px;
  color: var(--pp-muted);
  font-size: 14px;
  font-weight: 500;
  text-decoration: none;
}
.nav-link:hover { background: var(--pp-bg); color: var(--pp-text); }
.nav-link.router-link-exact-active { background: var(--pp-primary-soft); color: var(--pp-primary-ink); font-weight: 600; }
.foot { margin-top: auto; display: flex; flex-direction: column; gap: 14px; }
.foot p { margin: 0; padding: 0 4px; font-size: 12px; color: var(--pp-muted); }
.segmented { display: flex; padding: 4px; border-radius: 12px; background: var(--pp-bg); }
.segmented button {
  flex: 1;
  min-height: 34px;
  border: 0;
  border-radius: 9px;
  background: transparent;
  color: var(--pp-muted);
  font: inherit;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
}
.segmented button[aria-pressed='true'] { background: var(--pp-surface); color: var(--pp-text); box-shadow: 0 1px 2px rgba(18, 20, 29, 0.14); }

.content { min-width: 0; }
.topbar, .tabbar { display: none; }

@media (max-width: 800px) {
  .shell { display: block; }
  .sidebar { display: none; }
  .topbar {
    position: sticky;
    top: 0;
    z-index: 10;
    height: var(--topbar-h);
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 0 14px;
    background: var(--pp-surface);
    border-bottom: 1px solid var(--pp-border);
  }
  .brand { padding: 0; font-size: 17px; }
  .lang {
    min-width: 44px;
    min-height: 44px;
    padding: 0 14px;
    border: 1px solid var(--pp-border);
    border-radius: 12px;
    background: var(--pp-bg);
    color: var(--pp-text);
    font: inherit;
    font-size: 14px;
    font-weight: 600;
    cursor: pointer;
  }
  .tabbar {
    position: fixed;
    inset-inline: 0;
    bottom: 0;
    z-index: 10;
    height: var(--tabbar-h);
    display: flex;
    background: var(--pp-surface);
    border-top: 1px solid var(--pp-border);
  }
  .tab {
    flex: 1;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 2px;
    color: var(--pp-muted);
    font-size: 11.5px;
    font-weight: 500;
    text-decoration: none;
  }
  .tab.router-link-exact-active { color: var(--pp-primary-ink); font-weight: 600; }
}
</style>
