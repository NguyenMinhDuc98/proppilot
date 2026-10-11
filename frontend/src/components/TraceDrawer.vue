<script setup lang="ts">
import { computed, onMounted, ref, useId } from 'vue'
import { useI18n } from 'vue-i18n'
import { fetchRunTrace } from '../api/runs'
import type { RunTrace } from '../api/types'
import { buildStoredTrace, buildTrace, toolDetailsOf } from '../composables/trace'
import type { ChatMessage } from '../stores/chat'
import TraceTimeline from './TraceTimeline.vue'

const props = defineProps<{ message: ChatMessage }>()
const { t } = useI18n()

const open = ref(false)
const panelId = `trace-${useId()}`

/** The run as the server stored it; null until it was read, and for good when it could not be or holds no calls. */
const stored = ref<RunTrace | null>(null)

/**
 * The drawer is there once the answer is complete, and that is when its stored trace is read. Until it arrives, and
 * when it never does, the drawer shows what the stream already told: nothing waits on the request.
 */
onMounted(async () => {
  const runId = props.message.usage?.runId
  if (!runId) return
  try {
    const found = await fetchRunTrace(runId)
    // A run stored before traces were kept has its totals and no calls: the stream says more than that.
    if (found.llmCalls.length > 0) stored.value = found
  } catch {
    // The live trace stays.
  }
})

const trace = computed(() =>
  stored.value
    ? buildStoredTrace(stored.value, toolDetailsOf(props.message, stored.value), props.message.preamble)
    : buildTrace(props.message),
)
</script>

<template>
  <div class="trace">
    <button type="button" class="toggle" :aria-expanded="open" :aria-controls="panelId" @click="open = !open">
      <svg class="chevron mirror-rtl" :class="{ open }" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
        <path d="m9 6 6 6-6 6" />
      </svg>
      {{ t('trace.toggle') }}
    </button>

    <div v-show="open" :id="panelId" class="panel">
      <TraceTimeline :trace="trace" />
      <p class="note">{{ t('trace.readOnly') }}</p>
    </div>
  </div>
</template>

<style scoped>
.trace { margin-top: 10px; }
.toggle {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-height: 32px;
  padding: 0 10px 0 6px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: var(--pp-muted);
  font: inherit;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
}
.toggle:hover { background: var(--pp-primary-soft); color: var(--pp-primary-ink); }
.chevron { transition: transform 0.15s; }
.chevron.open { transform: rotate(90deg); }
.panel {
  margin-top: 6px;
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 16px;
  background: var(--pp-surface);
  border: 1px solid var(--pp-border);
  border-radius: 14px;
}
.note { margin: 0; font-size: 12px; color: var(--pp-muted); }
</style>
