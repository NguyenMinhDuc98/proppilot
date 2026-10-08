<script setup lang="ts">
import { Check, Loading, Warning } from '@element-plus/icons-vue'
import { ElIcon } from 'element-plus'
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { ToolChip } from '../stores/chat'

const props = defineProps<{ chip: ToolChip }>()
const { t, te, locale } = useI18n()

const label = computed(() => {
  const key = te(`tools.${props.chip.name}.running`) ? props.chip.name : 'unknown'
  return t(`tools.${key}.${props.chip.status === 'running' ? 'running' : 'done'}`)
})
</script>

<template>
  <span class="chip" :class="chip.status" :title="chip.summary">
    <ElIcon :class="{ spin: chip.status === 'running' }">
      <Loading v-if="chip.status === 'running'" />
      <Warning v-else-if="chip.status === 'failed'" />
      <Check v-else />
    </ElIcon>
    <span>{{ label }}</span>
    <!-- Summaries come from the server in English, so they are only inlined for English; others see them on hover. -->
    <span v-if="chip.summary && chip.status !== 'running' && locale === 'en'" class="summary">· {{ chip.summary }}</span>
  </span>
</template>

<style scoped>
.chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 3px 12px;
  font-size: 12px;
  font-weight: 600;
  border-radius: 999px;
  background: var(--pp-primary-soft);
  color: var(--pp-primary-ink);
}
.chip.done { background: var(--pp-success-bg); color: var(--pp-success-ink); }
.chip.failed { background: var(--pp-warn-bg); color: var(--pp-warn-ink); }
.summary { font-weight: 500; opacity: 0.85; }
.spin { animation: spin 1s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
</style>
