<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { formatCount, formatLatency } from '../composables/format'
import type { Trace } from '../composables/trace'

const props = defineProps<{ trace: Trace }>()
const { t, te } = useI18n()

const titleKey = {
  note: 'note', model: 'modelCall', call: 'called', result: 'returned', failed: 'failed', answer: 'answered',
} as const

/** The rules the question met, in words; one the app has no words for is shown as the server named it. */
const complexity = computed(() => {
  const found = props.trace.complexity
  if (!found) return ''
  if (!found.rules.length) return t('trace.complexity', { score: found.score })
  const rules = found.rules.map((rule) => (te(`trace.rules.${rule}`) ? t(`trace.rules.${rule}`) : rule))
  return t('trace.complexityWith', { score: found.score, rules: rules.join(', ') })
})

const stopLabel = (reason: string | null) => (reason && te(`trace.stop.${reason}`) ? t(`trace.stop.${reason}`) : '')

/** A call that failed is explained with the same words as a failed answer, never the provider's own text. */
const failureLabel = (code: string) => t(te(`chat.errors.${code}`) ? `chat.errors.${code}` : 'chat.errors.generic')
</script>

<template>
  <div class="timeline">
    <div v-if="trace.routing || complexity" class="routing">
      <span v-if="trace.routing" class="chip">{{ t(`trace.routed.${trace.routing}`) }}</span>
      <span v-if="complexity" class="complexity">{{ complexity }}</span>
    </div>

    <ol class="steps">
      <li v-for="(step, i) in trace.steps" :key="i" class="step" :class="[step.kind, { problem: step.model?.errorCode }]">
        <span class="num">{{ i + 1 }}</span>
        <div v-if="step.kind === 'model' && step.model" class="step-body">
          <span class="step-title">
            {{ t(step.model.errorCode ? 'trace.modelFailed' : 'trace.modelCall', { n: step.model.seq }) }}
          </span>
          <span class="model-name"><bdi>{{ step.model.name }}</bdi></span>
          <span v-if="step.model.errorCode" class="detail">{{ failureLabel(step.model.errorCode) }}</span>
          <template v-else>
            <span class="detail">
              <bdi>{{ t('trace.tokens', { in: formatCount(step.model.tokensIn), out: formatCount(step.model.tokensOut) }) }}</bdi>
              ·
              <bdi>{{ formatLatency(step.model.latencyMs) }}</bdi>
              <template v-if="stopLabel(step.model.stopReason)"> · {{ stopLabel(step.model.stopReason) }}</template>
            </span>
            <span v-if="step.model.cacheRead > 0 || step.model.cacheWrite > 0" class="detail dim">
              <bdi v-if="step.model.cacheRead > 0">{{ t('trace.cacheRead', { n: formatCount(step.model.cacheRead) }) }}</bdi>
              <template v-if="step.model.cacheRead > 0 && step.model.cacheWrite > 0"> · </template>
              <bdi v-if="step.model.cacheWrite > 0">{{ t('trace.cacheWritten', { n: formatCount(step.model.cacheWrite) }) }}</bdi>
            </span>
          </template>
        </div>
        <div v-else class="step-body">
          <span class="step-title">{{ t(`trace.${titleKey[step.kind]}`) }}</span>
          <span v-if="step.kind === 'call'" class="call">
            <code class="mono">{{ step.main }}</code>
            <bdi v-if="step.latencyMs !== undefined" class="latency">{{ formatLatency(step.latencyMs) }}</bdi>
          </span>
          <code v-if="step.detail && step.detail !== '{}'" class="mono dim">{{ step.detail }}</code>
          <span v-if="step.kind === 'note'" class="detail note-text" dir="auto">{{ step.main }}</span>
          <span v-if="(step.kind === 'result' || step.kind === 'failed') && step.main" class="detail" dir="auto">{{ step.main }}</span>
          <span v-if="step.kind === 'answer'" class="detail">{{ t('trace.answerTokens', { n: step.tokens ?? 0 }) }}</span>
        </div>
      </li>
    </ol>

    <p v-if="trace.guardrail" class="check" :class="trace.guardrail.toLowerCase()">
      <span class="check-label">{{ t('trace.check') }}</span>
      {{ t(`trace.checks.${trace.guardrail}`) }}
    </p>

    <dl v-if="trace.stats.length" class="stats">
      <div v-for="stat in trace.stats" :key="stat.id" class="stat">
        <dt>{{ t(`trace.stats.${stat.id}`) }}</dt>
        <dd><bdi>{{ stat.value }}</bdi></dd>
      </div>
    </dl>
  </div>
</template>

<style scoped>
.timeline { display: flex; flex-direction: column; gap: 16px; }
.routing { display: flex; flex-wrap: wrap; align-items: center; gap: 6px 10px; }
.chip {
  padding: 4px 12px;
  border-radius: 12px;
  background: var(--pp-primary-soft);
  color: var(--pp-primary-ink);
  font-size: 12.5px;
  font-weight: 600;
}
.complexity { font-size: 12.5px; color: var(--pp-muted); }
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
.step.failed .num, .step.problem .num { background: var(--pp-danger-bg); color: var(--pp-danger-ink); }
.step.model .num { background: var(--pp-surface-2); color: var(--pp-text); box-shadow: inset 0 0 0 1px var(--pp-border-strong); }
.step-body { min-width: 0; display: flex; flex-direction: column; gap: 2px; }
.step-title { font-size: 12px; color: var(--pp-muted); font-weight: 600; }
.model-name { font-size: 14px; font-weight: 700; }
.call { display: flex; flex-wrap: wrap; align-items: baseline; gap: 2px 10px; }
.latency { font-size: 12px; color: var(--pp-muted); font-variant-numeric: tabular-nums; }
.mono { font-family: ui-monospace, 'SF Mono', Menlo, Consolas, monospace; font-size: 12.5px; overflow-wrap: anywhere; direction: ltr; text-align: start; }
.detail { font-size: 13px; overflow-wrap: anywhere; font-variant-numeric: tabular-nums; }
.note-text { white-space: pre-line; }
.dim { color: var(--pp-muted); }
.check { margin: 0; padding: 8px 12px; border-radius: 10px; background: var(--pp-bg); font-size: 13px; }
.check-label { display: block; font-size: 11.5px; font-weight: 600; color: var(--pp-muted); }
.check.flagged, .check.retried_flagged { background: var(--pp-warn-bg); color: var(--pp-warn-ink); }
.check.flagged .check-label, .check.retried_flagged .check-label { color: inherit; }
.stats { margin: 0; display: grid; grid-template-columns: repeat(auto-fit, minmax(110px, 1fr)); gap: 8px; }
.stat { padding: 8px 12px; border-radius: 10px; background: var(--pp-bg); }
.stat dt { font-size: 11.5px; color: var(--pp-muted); }
.stat dd { margin: 0; font-size: 15px; font-weight: 700; font-variant-numeric: tabular-nums; }
</style>
