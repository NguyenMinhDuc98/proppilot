<script setup lang="ts">
import { ElTable, ElTableColumn } from 'element-plus'
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { fetchStats } from '../api/units'
import type { Stats } from '../api/types'
import { formatCost, formatLatency } from '../composables/format'

const { t, locale } = useI18n()
const stats = ref<Stats | null>(null)
const failed = ref(false)

const maxCalls = computed(() => Math.max(1, ...(stats.value?.toolUsage.map((u) => u.calls) ?? [])))
const toolLabel = (tool: string) => t(`tools.${tool}.done`)
const when = (iso: string) => new Date(iso).toLocaleString(locale.value === 'ar' ? 'ar-SA-u-nu-latn' : 'en-GB')

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
          <strong><bdi>{{ stats.totalInputTokens }} / {{ stats.totalOutputTokens }}</bdi></strong>
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

      <div class="card recent">
        <h2>{{ t('stats.recent') }}</h2>
        <ElTable :data="stats.recentRuns">
          <ElTableColumn :label="t('stats.when')" min-width="150"><template #default="{ row }">{{ when(row.at) }}</template></ElTableColumn>
          <ElTableColumn :label="t('stats.question')" prop="question" min-width="260" show-overflow-tooltip />
          <ElTableColumn :label="t('stats.tools')" prop="toolCalls" width="90" />
          <ElTableColumn :label="t('stats.latency')" width="110"><template #default="{ row }"><bdi>{{ formatLatency(row.latencyMs) }}</bdi></template></ElTableColumn>
          <ElTableColumn :label="t('stats.cost')" width="110"><template #default="{ row }">{{ formatCost(row.costUsd) }}</template></ElTableColumn>
        </ElTable>
      </div>
    </template>
  </div>
</template>

<style scoped>
.tiles { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 12px; margin-bottom: 12px; }
.tile { display: flex; flex-direction: column; gap: 6px; }
.tile span { font-size: 12px; color: var(--pp-muted); }
.tile strong { font-size: 22px; }
.bars, .recent { margin-bottom: 12px; }
h2 { margin: 0 0 12px; font-size: 16px; }
.bar-row { display: grid; grid-template-columns: 200px 1fr 40px; gap: 10px; align-items: center; margin-bottom: 8px; font-size: 13px; }
.bar { height: 10px; border-radius: 999px; background: var(--pp-bg); overflow: hidden; }
.fill { display: block; height: 100%; background: var(--pp-primary); border-radius: 999px; }
.bar-value { text-align: end; }
.muted { color: var(--pp-muted); }
@media (max-width: 640px) { .bar-row { grid-template-columns: 120px 1fr 32px; } }
</style>
