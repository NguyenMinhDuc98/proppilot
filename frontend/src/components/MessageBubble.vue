<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { formatCost, formatLatency } from '../composables/format'
import { renderMarkdown } from '../composables/markdown'
import type { ChatMessage } from '../stores/chat'
import ToolChip from './ToolChip.vue'
import TraceDrawer from './TraceDrawer.vue'

const props = defineProps<{ message: ChatMessage }>()
const { t, te } = useI18n()

const isUser = computed(() => props.message.role === 'user')
const html = computed(() => (isUser.value ? '' : renderMarkdown(props.message.text)))
const usage = computed(() => props.message.usage)
const showTrace = computed(() => !isUser.value && !props.message.streaming && props.message.tools.length > 0)
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
    t('chat.usage.tokens', { n: (u.inputTokens + u.outputTokens).toLocaleString('en-US') }),
  ]
})
// Server errors are shown from their code, so provider or server text never reaches the user.
const errorText = computed(() => {
  const { error, errorCode } = props.message
  if (errorCode) return t(te(`chat.errors.${errorCode}`) ? `chat.errors.${errorCode}` : 'chat.errors.generic')
  return error === 'network' ? t('chat.errorNetwork') : `${t('chat.errorPrefix')}: ${error}`
})
</script>

<template>
  <div class="msg" :class="{ user: isUser }">
    <div v-if="isUser" class="user-bubble" dir="auto">{{ message.text }}</div>

    <template v-else>
      <svg class="avatar" viewBox="0 0 32 32" width="32" height="32" aria-hidden="true">
        <rect width="32" height="32" rx="9" fill="currentColor" />
        <path d="M8 17 16 9l8 8v7h-5v-5h-6v5H8z" fill="var(--pp-surface)" />
      </svg>
      <div class="body">
        <div v-if="message.tools.length" class="tools">
          <ToolChip v-for="(chip, i) in message.tools" :key="i" :chip="chip" />
        </div>
        <!-- eslint-disable-next-line vue/no-v-html -- sanitised by DOMPurify in renderMarkdown -->
        <div v-if="message.text" class="markdown" dir="auto" v-html="html" />
        <div v-else-if="message.streaming && !message.tools.length" class="thinking">{{ t('chat.thinking') }}</div>
        <div v-if="usage?.truncated" class="notice" role="note">{{ t('chat.truncated') }}</div>
        <div v-if="message.error" class="error" role="alert">{{ errorText }}</div>
        <div v-if="usage" class="meta">
          <bdi v-for="(part, i) in usageParts" :key="i">{{ part }}</bdi>
        </div>
        <TraceDrawer v-if="showTrace" :message="message" />
      </div>
    </template>
  </div>
</template>

<style scoped>
.msg { display: flex; gap: 14px; align-items: flex-start; }
.msg.user { justify-content: flex-end; }
.user-bubble {
  max-width: min(80%, 560px);
  padding: 11px 18px;
  border-radius: 18px;
  border-end-end-radius: 5px;
  background: var(--pp-user-bubble);
  color: var(--pp-user-text);
  font-weight: 500;
  overflow-wrap: anywhere;
  white-space: pre-wrap;
  box-shadow: 0 6px 16px -8px var(--pp-user-bubble);
}
.avatar { flex: none; color: var(--pp-text); }
.body { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 12px; }
.tools { display: flex; flex-wrap: wrap; gap: 8px; }
.thinking { color: var(--pp-muted); }
.notice { align-self: flex-start; color: var(--pp-warn-ink); background: var(--pp-warn-bg); padding: 6px 12px; border-radius: 10px; font-size: 13px; }
.error { color: var(--pp-danger-ink); background: var(--pp-danger-bg); padding: 8px 12px; border-radius: 10px; }
.meta { display: flex; flex-wrap: wrap; gap: 2px 14px; font-size: 12px; color: var(--pp-muted); font-variant-numeric: tabular-nums; }

.markdown { text-align: start; overflow-wrap: anywhere; }
.markdown :deep(> :first-child) { margin-top: 0; }
.markdown :deep(> :last-child) { margin-bottom: 0; }
.markdown :deep(p) { margin: 0 0 10px; }
.markdown :deep(ul), .markdown :deep(ol) { margin: 0 0 10px; padding-inline-start: 22px; }
.markdown :deep(li + li) { margin-top: 3px; }
.markdown :deep(strong) { font-weight: 700; }
.markdown :deep(code) { background: var(--pp-primary-soft); color: var(--pp-primary-ink); padding: 1px 6px; border-radius: 6px; font-size: 0.9em; }
.markdown :deep(h1), .markdown :deep(h2), .markdown :deep(h3) { margin: 14px 0 6px; font-size: 15px; font-weight: 700; }
.markdown :deep(.table-wrap) {
  margin: 4px 0 12px;
  overflow-x: auto;
  background: var(--pp-surface);
  border: 1px solid var(--pp-border);
  border-radius: 14px;
  box-shadow: var(--pp-shadow);
}
.markdown :deep(table) { width: 100%; border-collapse: collapse; font-size: 14px; }
.markdown :deep(th) {
  padding: 10px 16px;
  text-align: start;
  white-space: nowrap;
  background: var(--pp-surface-2);
  border-bottom: 1px solid var(--pp-border);
  color: var(--pp-muted);
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.04em;
  text-transform: uppercase;
}
.markdown :deep(td) { padding: 10px 16px; border-bottom: 1px solid var(--pp-border); text-align: start; font-variant-numeric: tabular-nums; }
.markdown :deep(tr:last-child td) { border-bottom: 0; }

@media (max-width: 800px) {
  /* Keep each cell on one line and let the card scroll sideways instead of squeezing names and unit codes. */
  .markdown :deep(td), .markdown :deep(th) { white-space: nowrap; padding: 9px 12px; }
}
</style>
