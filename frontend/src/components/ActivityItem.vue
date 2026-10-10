<script setup lang="ts">
import { computed, ref, useId } from 'vue'
import { useI18n } from 'vue-i18n'
import type { ActionView, AuditEntry } from '../api/types'
import { formatDateTime } from '../composables/format'
import ActionKindIcon from './ActionKindIcon.vue'
import ActionStatusPill from './ActionStatusPill.vue'

const props = defineProps<{ action: ActionView; entries: AuditEntry[] }>()
const { t, te, locale } = useI18n()

const open = ref(false)
const trailId = `trail-${useId()}`
const summary = computed(() => (locale.value === 'ar' ? props.action.summaryAr : props.action.summaryEn))

/** The assistant and the system have names of their own; a visitor is shown by the name the server gave. */
const actorName = (actor: string) => (te(`activity.actors.${actor}`) ? t(`activity.actors.${actor}`) : actor)
const eventName = (event: string) => (te(`activity.events.${event}`) ? t(`activity.events.${event}`) : event)
const pretty = (value: unknown) => JSON.stringify(value, null, 2)
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

    <button type="button" class="toggle" :aria-expanded="open" :aria-controls="trailId" @click="open = !open">
      <svg class="chevron mirror-rtl" :class="{ open }" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="m9 6 6 6-6 6" /></svg>
      {{ t('activity.auditTrail') }} ({{ entries.length }})
    </button>

    <div v-show="open" :id="trailId" class="trail">
      <p v-if="!entries.length" class="empty">{{ t('activity.auditEmpty') }}</p>
      <ol v-else class="entries">
        <li v-for="entry in entries" :key="entry.id" class="entry">
          <div class="entry-head">
            <strong>{{ eventName(entry.event) }}</strong>
            <time :datetime="entry.at">{{ formatDateTime(entry.at, locale) }}</time>
            <bdi>{{ actorName(entry.actor) }}</bdi>
          </div>
          <p v-if="entry.note" class="note" dir="auto">{{ t('activity.note') }}: {{ entry.note }}</p>
          <div v-if="entry.before !== null" class="state">
            <span>{{ t('activity.before') }}</span>
            <pre class="mono" dir="ltr">{{ pretty(entry.before) }}</pre>
          </div>
          <div v-if="entry.after !== null" class="state">
            <span>{{ t('activity.after') }}</span>
            <pre class="mono" dir="ltr">{{ pretty(entry.after) }}</pre>
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
