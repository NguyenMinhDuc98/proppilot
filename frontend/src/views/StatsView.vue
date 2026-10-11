<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { fetchStats } from '../api/units'
import type { Stats } from '../api/types'
import { formatCost, formatCount, formatLatency, formatModel } from '../composables/format'

const { t } = useI18n()
const stats = ref<Stats | null>(null)
const failed = ref(false)

const maxCalls = computed(() => Math.max(1, ...(stats.value?.toolUsage.map((u) => u.calls) ?? [])))
const toolLabel = (tool: string) => t(`tools.${tool}.done`)

// The fields of a newer server are read as zero from an older one, which the page may be paired with for a while.
const models = computed(() => stats.value?.byModel ?? [])
const maxModelRuns = computed(() => Math.max(1, ...models.value.map((m) => m.runs)))
const cache = computed(() => stats.value?.cache ?? { readTokens: 0, writeTokens: 0 })
const flaggedRuns = computed(() => stats.value?.guardrail?.flaggedRuns ?? 0)
/** Everything the models read: `totalInputTokens` leaves out what the prompt cache served and took in. */
const inputTokens = computed(
  () => (stats.value?.totalInputTokens ?? 0) + cache.value.readTokens + cache.value.writeTokens,
)
/** Of everything the models read, the part the prompt cache served; absent when it served nothing. */
const cacheShare = computed(() => {
  const read = cache.value.readTokens
  return read > 0 && inputTokens.value > 0 ? `${Math.round((read / inputTokens.value) * 100)}%` : ''
})

onMounted(async () => {
  try {
    stats.value = await fetchStats()
  } catch {
    failed.value = true
  }
})
</script>

<template>
  <div class="page">
    <h1>{{ t('stats.title') }}</h1>
    <p class="subtitle">{{ t('stats.subtitle') }}</p>

    <p v-if="failed" class="muted">{{ t('chat.errorNetwork') }}</p>
    <p v-else-if="stats && stats.totalRuns === 0" class="muted">{{ t('stats.none') }}</p>

    <template v-if="stats && stats.totalRuns > 0">
      <div class="tiles">
        <div class="card tile"><span>{{ t('stats.questions') }}</span><strong>{{ stats.totalRuns }}</strong></div>
        <div class="card tile"><span>{{ t('stats.avgCost') }}</span><strong>{{ formatCost(stats.avgCostUsd) }}</strong></div>
        <div class="card tile"><span>{{ t('stats.avgLatency') }}</span><strong><bdi>{{ formatLatency(stats.avgLatencyMs) }}</bdi></strong></div>
        <div class="card tile"><span>{{ t('stats.avgTools') }}</span><strong>{{ stats.avgToolCalls }}</strong></div>
        <div class="card tile"><span>{{ t('stats.totalCost') }}</span><strong>{{ formatCost(stats.totalCostUsd) }}</strong></div>
        <div class="card tile">
          <span>{{ t('stats.tokens') }}</span>
          <strong><bdi>{{ formatCount(inputTokens) }} / {{ formatCount(stats.totalOutputTokens) }}</bdi></strong>
        </div>
      </div>

      <div class="card bars">
        <h2>{{ t('stats.toolUsage') }}</h2>
        <div v-for="usage in stats.toolUsage" :key="usage.tool" class="bar-row">
          <span class="bar-label">{{ toolLabel(usage.tool) }}</span>
          <span class="bar"><span class="fill" :style="{ width: `${(usage.calls / maxCalls) * 100}%` }" /></span>
          <span class="bar-value">{{ usage.calls }}</span>
        </div>
      </div>

      <section class="card block" aria-labelledby="stats-models">
        <h2 id="stats-models">{{ t('stats.models') }}</h2>
        <p v-if="!models.length" class="muted">{{ t('stats.modelsNone') }}</p>
        <template v-else>
          <div class="bar-row model-row head" aria-hidden="true">
            <span>{{ t('stats.modelsHead.model') }}</span>
            <span />
            <span class="bar-value">{{ t('stats.modelsHead.questions') }}</span>
            <span class="bar-value">{{ t('stats.modelsHead.cost') }}</span>
          </div>
          <div v-for="usage in models" :key="usage.model" class="bar-row model-row">
            <bdi class="bar-label">{{ formatModel(usage.model) }}</bdi>
            <span class="bar"><span class="fill" :style="{ width: `${(usage.runs / maxModelRuns) * 100}%` }" /></span>
            <bdi class="bar-value">{{ usage.runs }}</bdi>
            <bdi class="bar-value">{{ formatCost(usage.costUsd) }}</bdi>
          </div>
        </template>
      </section>

      <section class="card block" aria-labelledby="stats-cache">
        <h2 id="stats-cache">{{ t('stats.cache') }}</h2>
        <p v-if="!cache.readTokens && !cache.writeTokens" class="muted">{{ t('stats.cacheNone') }}</p>
        <div v-else class="figures">
          <div class="figure"><span>{{ t('stats.cacheRead') }}</span><strong><bdi>{{ formatCount(cache.readTokens) }}</bdi></strong></div>
          <div class="figure"><span>{{ t('stats.cacheWrite') }}</span><strong><bdi>{{ formatCount(cache.writeTokens) }}</bdi></strong></div>
          <div v-if="cacheShare" class="figure"><span>{{ t('stats.cacheShare') }}</span><strong><bdi>{{ cacheShare }}</bdi></strong></div>
        </div>
      </section>

      <section class="card block" aria-labelledby="stats-flagged">
        <h2 id="stats-flagged">{{ t('stats.flagged') }}</h2>
        <p v-if="!flaggedRuns" class="muted">{{ t('stats.flaggedNone') }}</p>
        <div v-else class="figures">
          <div class="figure"><strong><bdi>{{ formatCount(flaggedRuns) }}</bdi></strong><span>{{ t('stats.flaggedSome') }}</span></div>
        </div>
      </section>
    </template>
  </div>
</template>

<style scoped>
.tiles { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 12px; margin-bottom: 12px; }
.tile { display: flex; flex-direction: column; gap: 6px; }
.tile span { font-size: 12px; color: var(--pp-muted); }
.tile strong { font-size: 22px; }
.bars, .block { margin-bottom: 12px; }
h2 { margin: 0 0 12px; font-size: 16px; }
.figures { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 12px; }
.figure { display: flex; flex-direction: column; gap: 4px; }
.figure span { font-size: 12px; color: var(--pp-muted); }
.figure strong { font-size: 22px; font-variant-numeric: tabular-nums; }
.bar-row { display: grid; grid-template-columns: 200px 1fr 40px; gap: 10px; align-items: center; margin-bottom: 8px; font-size: 13px; }
.bar { height: 10px; border-radius: 999px; background: var(--pp-bg); overflow: hidden; }
.fill { display: block; height: 100%; background: var(--pp-primary); border-radius: 999px; }
.bar-value { text-align: end; }
.model-row { grid-template-columns: 200px 1fr 56px 84px; }
.model-row.head { margin-bottom: 4px; color: var(--pp-muted); font-size: 12px; font-weight: 600; }
.muted { color: var(--pp-muted); }
@media (max-width: 640px) {
  .bar-row { grid-template-columns: 120px 1fr 32px; }
  .model-row { grid-template-columns: 110px 1fr 40px 70px; }
}
</style>
