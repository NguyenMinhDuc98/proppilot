<script setup lang="ts">
import { ElConfigProvider } from 'element-plus'
import { useI18n } from 'vue-i18n'
import { useLocale } from './i18n'

const { t } = useI18n()
const { elementLocale, toggle } = useLocale()
</script>

<template>
  <ElConfigProvider :locale="elementLocale">
    <header class="top">
      <div class="inner">
        <RouterLink to="/" class="brand">
          <svg viewBox="0 0 32 32" width="26" height="26" aria-hidden="true">
            <rect width="32" height="32" rx="8" fill="#2563eb" />
            <path d="M8 17 16 9l8 8v7h-5v-5h-6v5H8z" fill="#fff" />
          </svg>
          <span>{{ t('app.title') }}</span>
        </RouterLink>
        <nav>
          <RouterLink to="/">{{ t('nav.chat') }}</RouterLink>
          <RouterLink to="/units">{{ t('nav.units') }}</RouterLink>
          <RouterLink to="/stats">{{ t('nav.stats') }}</RouterLink>
        </nav>
        <button class="lang" type="button" @click="toggle">{{ t('app.language') }}</button>
      </div>
    </header>
    <main>
      <RouterView />
    </main>
  </ElConfigProvider>
</template>

<style scoped>
.top { background: var(--pp-surface); border-bottom: 1px solid var(--pp-border); position: sticky; top: 0; z-index: 10; }
.inner { max-width: 960px; margin-inline: auto; padding: 10px 16px; display: flex; align-items: center; gap: 20px; }
.brand { display: flex; align-items: center; gap: 8px; font-weight: 700; color: var(--pp-text); text-decoration: none; }
nav { display: flex; gap: 4px; flex: 1; }
nav a { padding: 6px 12px; border-radius: 8px; color: var(--pp-muted); text-decoration: none; }
nav a.router-link-exact-active { background: var(--pp-bg); color: var(--pp-primary); font-weight: 600; }
.lang {
  font: inherit; cursor: pointer; padding: 6px 12px; border-radius: 8px;
  border: 1px solid var(--pp-border); background: var(--pp-bg); color: var(--pp-text);
}
@media (max-width: 520px) { .inner { gap: 8px; padding-inline: 10px; } nav a { padding-inline: 8px; } .brand span { display: none; } }
</style>
