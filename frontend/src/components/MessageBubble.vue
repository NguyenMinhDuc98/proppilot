<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { formatCost, formatLatency } from '../composables/format'
import type { ChatMessage } from '../stores/chat'
import ToolChip from './ToolChip.vue'

const props = defineProps<{ message: ChatMessage }>()
const { t } = useI18n()

const isUser = computed(() => props.message.role === 'user')
const usage = computed(() => props.message.usage)
const costLabel = computed(() =>
  usage.value?.provider === 'offline' ? t('chat.usage.free') : formatCost(usage.value?.costUsd ?? 0),
)
// Each part is isolated (<bdi>) so numbers and units keep their order inside right-to-left text.
const usageParts = computed(() => {
  const u = usage.value
  if (!u) return []
  return [
    costLabel.value,
    formatLatency(u.latencyMs),
    t('chat.usage.toolCalls', { n: u.toolCalls }),
    t('chat.usage.tokens', { n: u.inputTokens + u.outputTokens }),
  ]
})
const errorText = computed(() =>
  props.message.error === 'network' ? t('chat.errorNetwork') : `${t('chat.errorPrefix')}: ${props.message.error}`,
)
</script>

<template>
  <div class="row" :class="{ user: isUser }">
    <div class="bubble" :class="{ user: isUser }">
      <div v-if="message.tools.length" class="tools">
        <ToolChip v-for="(chip, i) in message.tools" :key="i" :chip="chip" />
      </div>
      <div v-if="message.text" class="text" dir="auto">{{ message.text }}</div>
      <div v-else-if="message.streaming && !message.tools.length" class="muted">{{ t('chat.thinking') }}</div>
      <div v-if="message.error" class="error">{{ errorText }}</div>
      <div v-if="usage" class="usage">
        <bdi v-for="(part, i) in usageParts" :key="i">{{ part }}</bdi>
      </div>
    </div>
  </div>
</template>

<style scoped>
.row { display: flex; }
.row.user { justify-content: flex-end; }
.bubble {
  max-width: min(680px, 92%);
  padding: 10px 14px;
  border-radius: var(--pp-radius);
  background: var(--pp-surface);
  border: 1px solid var(--pp-border);
}
.bubble.user {
  background: var(--pp-user-bubble);
  border-color: transparent;
  color: var(--pp-user-text);
}
.tools { display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 8px; }
.text { white-space: pre-wrap; overflow-wrap: anywhere; line-height: 1.6; text-align: start; }
.muted { color: var(--pp-muted); }
.error { margin-top: 6px; color: #dc2626; }
.usage { margin-top: 8px; font-size: 12px; color: var(--pp-muted); display: flex; flex-wrap: wrap; gap: 4px 12px; }
</style>
