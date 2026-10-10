<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import type { ActionState } from '../api/types'

defineProps<{ state: ActionState }>()
const { t } = useI18n()
</script>

<template>
  <span class="pill" :class="state.toLowerCase()">{{ t(`actions.status.${state}`) }}</span>
</template>

<style scoped>
.pill {
  flex: none;
  padding: 2px 10px;
  border-radius: 999px;
  background: var(--pp-primary-soft);
  color: var(--pp-primary-ink);
  font-size: 12px;
  font-weight: 700;
  white-space: nowrap;
}
.approved { background: var(--pp-success-bg); color: var(--pp-success-ink); }
.failed { background: var(--pp-danger-bg); color: var(--pp-danger-ink); }
.expired { background: var(--pp-warn-bg); color: var(--pp-warn-ink); }
.rejected { background: var(--pp-surface-2); color: var(--pp-muted); box-shadow: inset 0 0 0 1px var(--pp-border-strong); }
</style>
