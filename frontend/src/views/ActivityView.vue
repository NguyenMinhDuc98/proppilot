<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { fetchActions, fetchAudit } from '../api/actions'
import type { ActionView, AuditEntry } from '../api/types'
import ActivityItem from '../components/ActivityItem.vue'

const { t } = useI18n()
const actions = ref<ActionView[]>([])
const trails = ref<Record<string, AuditEntry[]>>({})
const loading = ref(true)
const failed = ref(false)

/** The audit log comes newest first; a trail reads from the proposal onwards. */
function groupByAction(entries: AuditEntry[]): Record<string, AuditEntry[]> {
  const grouped: Record<string, AuditEntry[]> = {}
  for (const entry of [...entries].reverse()) {
    if (entry.actionId) (grouped[entry.actionId] ??= []).push(entry)
  }
  return grouped
}

async function load() {
  loading.value = true
  failed.value = false
  try {
    const [latest, entries] = await Promise.all([fetchActions(), fetchAudit()])
    actions.value = latest
    trails.value = groupByAction(entries)
  } catch {
    failed.value = true
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="head">
      <div>
        <h1>{{ t('activity.title') }}</h1>
        <p class="subtitle">{{ t('activity.subtitle') }}</p>
      </div>
      <button type="button" class="pp-button" :disabled="loading" @click="load">{{ t('activity.refresh') }}</button>
    </div>

    <p class="banner" role="note">{{ t('activity.banner') }}</p>

    <p v-if="loading && !actions.length" class="muted" role="status">{{ t('activity.loading') }}</p>

    <div v-else-if="failed" class="card problem" role="alert">
      <p>{{ t('activity.error') }}</p>
      <button type="button" class="pp-button" @click="load">{{ t('activity.retry') }}</button>
    </div>

    <p v-else-if="!actions.length" class="card muted">{{ t('activity.empty') }}</p>

    <ul v-else class="list">
      <ActivityItem v-for="action in actions" :key="action.id" :action="action" :entries="trails[action.id] ?? []" />
    </ul>
  </div>
</template>

<style scoped>
.head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; }
.head .subtitle { margin-bottom: 14px; }
.banner {
  margin: 0 0 16px;
  padding: 10px 14px;
  border-radius: 12px;
  background: var(--pp-primary-soft);
  color: var(--pp-primary-ink);
  font-size: 13.5px;
  font-weight: 600;
}
.list { margin: 0; padding: 0; display: flex; flex-direction: column; gap: 12px; }
.muted { color: var(--pp-muted); }
.problem { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 12px; color: var(--pp-danger-ink); background: var(--pp-danger-bg); }
.problem p { margin: 0; }
</style>
