<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { formatModel } from '../composables/format'
import { useAppInfo } from '../composables/useAppInfo'

const { t } = useI18n()
const { info } = useAppInfo()

const label = computed(() => {
  if (!info.value) return ''
  return info.value.mode === 'offline' ? t('app.offlineDemo') : formatModel(info.value.model)
})
</script>

<template>
  <span v-if="label" class="badge" :class="info?.mode" :title="label">
    <span class="dot" />
    <span class="label">{{ label }}</span>
  </span>
</template>

<style scoped>
.badge {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  padding: 5px 12px;
  border-radius: 999px;
  background: var(--pp-surface);
  border: 1px solid var(--pp-border);
  color: var(--pp-muted);
  font-size: 12px;
  font-weight: 600;
}
.dot { flex: none; width: 8px; height: 8px; border-radius: 50%; background: var(--pp-success-ink); }
.label { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.offline .dot { background: var(--pp-warn-ink); }
</style>
