<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { AdminRun } from '../api/types'
import { formatCost, formatDateTime, formatLatency, formatModel, truncate } from '../composables/format'

const QUESTION_LENGTH = 140

const props = defineProps<{ run: AdminRun }>()
defineEmits<{ open: [] }>()
const { t, locale } = useI18n()

const question = computed(() => truncate(props.run.question, QUESTION_LENGTH))
/** The default model needs no label in a list of runs that mostly went to it. */
const routed = computed(() => (props.run.routedReason && props.run.routedReason !== 'DEFAULT' ? props.run.routedReason : null))
/** A run that was checked and found fine is the ordinary one, so only the others are marked. */
const checked = computed(() => (props.run.guardrailStatus && props.run.guardrailStatus !== 'OK' ? props.run.guardrailStatus : null))
const toolCount = computed(() => props.run.toolNames.length)
</script>

<template>
  <li class="row">
    <button type="button" class="run" @click="$emit('open')">
      <span class="head">
        <time :datetime="run.createdAt">{{ formatDateTime(run.createdAt, locale) }}</time>
        <span class="pill" :class="run.status.toLowerCase()">{{ t(`admin.statuses.${run.status}`) }}</span>
        <span v-if="checked" class="pill" :class="checked.toLowerCase()">{{ t(`admin.guardrail.${checked}`) }}</span>
        <span v-if="routed" class="pill routed">{{ t(`admin.routed.${routed}`) }}</span>
      </span>
      <span class="question" dir="auto">{{ question }}</span>
      <span class="meta">
        <bdi>{{ formatModel(run.model) }}</bdi>
        <bdi>{{ formatCost(run.costUsd) }}</bdi>
        <bdi>{{ formatLatency(run.latencyMs) }}</bdi>
        <span>{{ t('admin.tools', { n: toolCount }, toolCount) }}</span>
      </span>
    </button>
  </li>
</template>

<style scoped>
.row { list-style: none; }
.run {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 12px 14px;
  border: 1px solid var(--pp-border);
  border-radius: 14px;
  background: var(--pp-surface);
  color: inherit;
  font: inherit;
  text-align: start;
  cursor: pointer;
}
.run:hover { border-color: var(--pp-primary); }
.head { display: flex; flex-wrap: wrap; align-items: center; gap: 6px 10px; }
.head time { font-size: 12.5px; color: var(--pp-muted); font-variant-numeric: tabular-nums; }
.pill {
  padding: 1px 10px;
  border-radius: 999px;
  background: var(--pp-primary-soft);
  color: var(--pp-primary-ink);
  font-size: 12px;
  font-weight: 700;
  white-space: nowrap;
}
.pill.ok { background: var(--pp-success-bg); color: var(--pp-success-ink); }
.pill.error, .pill.aborted { background: var(--pp-danger-bg); color: var(--pp-danger-ink); }
.pill.truncated, .pill.max_iterations, .pill.flagged, .pill.retried_flagged { background: var(--pp-warn-bg); color: var(--pp-warn-ink); }
.pill.retried_ok { background: var(--pp-success-bg); color: var(--pp-success-ink); }
.question { overflow-wrap: anywhere; }
.meta { display: flex; flex-wrap: wrap; gap: 2px 14px; font-size: 12.5px; color: var(--pp-muted); font-variant-numeric: tabular-nums; }
</style>
