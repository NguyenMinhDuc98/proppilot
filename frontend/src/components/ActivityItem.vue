<script setup lang="ts">
import { computed, ref, useId, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { fetchAudit } from '../api/actions'
import type { ActionView, AuditEntry } from '../api/types'
import { formatDateTime } from '../composables/format'
import ActionKindIcon from './ActionKindIcon.vue'
import ActionStatusPill from './ActionStatusPill.vue'

const props = defineProps<{ action: ActionView }>()
const { t, te, locale } = useI18n()

const open = ref(false)
/** The entries of this action, oldest first; null until they were read, because only this action's are asked for. */
const entries = ref<AuditEntry[] | null>(null)
const loading = ref(false)
const failed = ref(false)
let latestRead = 0
const trailId = `trail-${useId()}`
const summary = computed(() => (locale.value === 'ar' ? props.action.summaryAr : props.action.summaryEn))
const toggleLabel = computed(() =>
  entries.value ? `${t('activity.auditTrail')} (${entries.value.length})` : t('activity.auditTrail'),
)

/** The assistant and the system have names of their own; a visitor is shown by the name the server gave. */
const actorName = (actor: string) => (te(`activity.actors.${actor}`) ? t(`activity.actors.${actor}`) : actor)
const eventName = (event: string) => (te(`activity.events.${event}`) ? t(`activity.events.${event}`) : event)
const pretty = (value: unknown) => JSON.stringify(value, null, 2)
/** Names a scrollable block for a screen reader, which would otherwise meet several alike. */
const stateLabel = (entry: AuditEntry, state: string) => `${eventName(entry.event)}: ${state}`

/** The server answers newest first; a trail reads from the proposal onwards. */
async function load() {
  const read = ++latestRead
  loading.value = true
  failed.value = false
  try {
    const found = await fetchAudit(props.action.id)
    if (read === latestRead) entries.value = [...found].reverse()
  } catch {
    if (read === latestRead) failed.value = true
  } finally {
    if (read === latestRead) loading.value = false
  }
}

/** Drops what was read, and any read still on its way, so that the next read shows the action as it is by then. */
function forget() {
  latestRead++
  entries.value = null
  loading.value = false
  failed.value = false
}

function toggle() {
  open.value = !open.value
  if (open.value && entries.value === null && !loading.value) void load()
}

// A refreshed list brings a new object for every action: a trail that is open is read again, a closed one when opened.
watch(
  () => props.action,
  () => {
    forget()
    if (open.value) void load()
  },
)
</script>

<template>
  <li class="item card">
    <div class="top">
      <ActionStatusPill :state="action.status" />
      <span class="kind">
        <ActionKindIcon :kind="action.kind" :size="16" />
        {{ t(`actions.kinds.${action.kind}`) }}
      </span>
      <time class="time" :datetime="action.createdAt">{{ formatDateTime(action.createdAt, locale) }}</time>
    </div>

    <p class="summary" dir="auto">{{ summary }}</p>
    <i18n-t v-if="action.actor" keypath="activity.decidedBy" tag="p" class="actor">
      <template #actor><bdi>{{ action.actor }}</bdi></template>
    </i18n-t>
    <p v-else class="actor">{{ t('activity.undecided') }}</p>

    <button type="button" class="toggle" :aria-expanded="open" :aria-controls="trailId" @click="toggle">
      <svg class="chevron mirror-rtl" :class="{ open }" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="m9 6 6 6-6 6" /></svg>
      {{ toggleLabel }}
    </button>

    <div v-show="open" :id="trailId" class="trail">
      <p v-if="loading" class="empty" role="status">{{ t('activity.auditLoading') }}</p>
      <div v-else-if="failed" class="empty problem" role="alert">
        <p>{{ t('activity.auditError') }}</p>
        <button type="button" class="pp-button" @click="load">{{ t('activity.retry') }}</button>
      </div>
      <p v-else-if="entries && !entries.length" class="empty">{{ t('activity.auditEmpty') }}</p>
      <ol v-else-if="entries" class="entries">
        <li v-for="entry in entries" :key="entry.id" class="entry">
          <div class="entry-head">
            <strong>{{ eventName(entry.event) }}</strong>
            <time :datetime="entry.at">{{ formatDateTime(entry.at, locale) }}</time>
            <bdi>{{ actorName(entry.actor) }}</bdi>
          </div>
          <p v-if="entry.note" class="note" dir="auto">{{ t('activity.note') }}: {{ entry.note }}</p>
          <div v-if="entry.before !== null" class="state">
            <span>{{ t('activity.before') }}</span>
            <pre class="mono" dir="ltr" tabindex="0" role="region" :aria-label="stateLabel(entry, t('activity.before'))">{{ pretty(entry.before) }}</pre>
          </div>
          <div v-if="entry.after !== null" class="state">
            <span>{{ t('activity.after') }}</span>
            <pre class="mono" dir="ltr" tabindex="0" role="region" :aria-label="stateLabel(entry, t('activity.after'))">{{ pretty(entry.after) }}</pre>
          </div>
        </li>
      </ol>
    </div>
  </li>
</template>

<style scoped>
.item { display: flex; flex-direction: column; gap: 8px; min-width: 0; list-style: none; }
.top { display: flex; flex-wrap: wrap; align-items: center; gap: 8px 12px; }
.kind { display: inline-flex; align-items: center; gap: 6px; font-size: 13px; font-weight: 600; color: var(--pp-muted); }
.time { margin-inline-start: auto; font-size: 12.5px; color: var(--pp-muted); font-variant-numeric: tabular-nums; }
.summary { margin: 0; overflow-wrap: anywhere; }
.actor { margin: 0; font-size: 12.5px; color: var(--pp-muted); }
.toggle {
  align-self: flex-start;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-height: 36px;
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
.trail { padding: 12px; border-radius: 12px; background: var(--pp-surface-2); border: 1px solid var(--pp-border); }
.empty { margin: 0; font-size: 13px; color: var(--pp-muted); }
.problem { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 8px 12px; color: var(--pp-danger-ink); }
.problem p { margin: 0; }
.entries { margin: 0; padding: 0; list-style: none; display: flex; flex-direction: column; gap: 14px; }
.entry { display: flex; flex-direction: column; gap: 6px; min-width: 0; }
.entry + .entry { padding-top: 14px; border-top: 1px solid var(--pp-border); }
.entry-head { display: flex; flex-wrap: wrap; gap: 4px 12px; align-items: baseline; font-size: 13px; }
.entry-head time, .entry-head bdi { color: var(--pp-muted); font-size: 12.5px; }
.note { margin: 0; font-size: 13px; overflow-wrap: anywhere; }
.state { display: flex; flex-direction: column; gap: 2px; font-size: 12px; font-weight: 600; color: var(--pp-muted); }
@media (max-width: 800px) {
  .toggle { min-height: 44px; }
}
.mono {
  margin: 0;
  padding: 8px 10px;
  max-height: 260px;
  overflow: auto;
  border-radius: 8px;
  background: var(--pp-bg);
  color: var(--pp-text);
  font-family: ui-monospace, 'SF Mono', Menlo, Consolas, monospace;
  font-size: 12px;
  font-weight: 400;
  text-align: start;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
</style>
