<script setup lang="ts">
import { computed, ref, useId } from 'vue'
import { useI18n } from 'vue-i18n'
import { buildTrace } from '../composables/trace'
import type { ChatMessage } from '../stores/chat'

const props = defineProps<{ message: ChatMessage }>()
const { t } = useI18n()

const open = ref(false)
const panelId = `trace-${useId()}`
const trace = computed(() => buildTrace(props.message))

const titleKey = { call: 'called', result: 'returned', failed: 'failed', answer: 'answered' } as const
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
      <ol class="steps">
        <li v-for="(step, i) in trace.steps" :key="i" class="step" :class="step.kind">
          <span class="num">{{ i + 1 }}</span>
          <div class="step-body">
            <span class="step-title">{{ t(`trace.${titleKey[step.kind]}`) }}</span>
            <code v-if="step.kind === 'call'" class="mono">{{ step.main }}</code>
            <code v-if="step.detail && step.detail !== '{}'" class="mono dim">{{ step.detail }}</code>
            <span v-if="step.kind === 'result' || step.kind === 'failed'" class="detail" dir="auto">{{ step.main }}</span>
            <span v-if="step.kind === 'answer'" class="detail">{{ t('trace.answerTokens', { n: step.tokens ?? 0 }) }}</span>
          </div>
        </li>
      </ol>

      <dl v-if="trace.stats.length" class="stats">
        <div v-for="stat in trace.stats" :key="stat.id" class="stat">
          <dt>{{ t(`trace.stats.${stat.id}`) }}</dt>
          <dd><bdi>{{ stat.value }}</bdi></dd>
        </div>
      </dl>
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
.steps { margin: 0; padding: 0; list-style: none; display: flex; flex-direction: column; }
.step { display: flex; gap: 12px; padding-bottom: 14px; position: relative; }
.step:last-child { padding-bottom: 0; }
.step:not(:last-child)::before {
  content: '';
  position: absolute;
  inset-inline-start: 10px;
  top: 24px;
  bottom: 2px;
  width: 1px;
  background: var(--pp-border-strong);
}
.num {
  flex: none;
  width: 21px;
  height: 21px;
  display: grid;
  place-items: center;
  border-radius: 50%;
  background: var(--pp-primary-soft);
  color: var(--pp-primary-ink);
  font-size: 11px;
  font-weight: 700;
}
.step.failed .num { background: var(--pp-danger-bg); color: var(--pp-danger-ink); }
.step-body { min-width: 0; display: flex; flex-direction: column; gap: 2px; }
.step-title { font-size: 12px; color: var(--pp-muted); font-weight: 600; }
.mono { font-family: ui-monospace, 'SF Mono', Menlo, Consolas, monospace; font-size: 12.5px; overflow-wrap: anywhere; direction: ltr; text-align: start; }
.detail { font-size: 13px; overflow-wrap: anywhere; }
.dim { color: var(--pp-muted); }
.stats { margin: 0; display: grid; grid-template-columns: repeat(auto-fit, minmax(110px, 1fr)); gap: 8px; }
.stat { padding: 8px 12px; border-radius: 10px; background: var(--pp-bg); }
.stat dt { font-size: 11.5px; color: var(--pp-muted); }
.stat dd { margin: 0; font-size: 15px; font-weight: 700; font-variant-numeric: tabular-nums; }
.note { margin: 0; font-size: 12px; color: var(--pp-muted); }
</style>
