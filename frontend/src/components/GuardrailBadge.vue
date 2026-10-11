<script setup lang="ts">
import { computed, ref, useId } from 'vue'
import { useI18n } from 'vue-i18n'
import type { GuardrailStatus, UnverifiedValue } from '../api/types'
import GuardrailValues from './GuardrailValues.vue'

const props = defineProps<{ status: GuardrailStatus | null; values: UnverifiedValue[] }>()
const { t } = useI18n()

const open = ref(false)
const panelId = `unverified-${useId()}`

// Both flagged states read alike to a reader: the answer has figures nothing the assistant looked up supports.
const flagged = computed(() => props.status === 'FLAGGED' || props.status === 'RETRIED_FLAGGED')
const rechecked = computed(() => props.status === 'RETRIED_OK')
</script>

<template>
  <div v-if="flagged" class="guardrail">
    <div role="status">
      <button type="button" class="badge" :aria-expanded="open" :aria-controls="panelId" @click="open = !open">
        <svg class="icon" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
          <path d="M12 3 2 20h20L12 3zM12 10v4M12 17h.01" />
        </svg>
        <span class="label">{{ t('guardrail.flagged') }}</span>
        <svg class="chevron" :class="{ open }" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
          <path d="m6 9 6 6 6-6" />
        </svg>
      </button>
    </div>

    <div v-show="open" :id="panelId" class="popover">
      <p class="intro">{{ t('guardrail.intro') }}</p>
      <GuardrailValues v-if="values.length" :values="values" class="list" />
      <p class="hint">{{ t('guardrail.hint') }}</p>
    </div>
  </div>

  <p v-else-if="rechecked" class="rechecked" role="status" :title="t('guardrail.recheckedHint')">
    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
      <path d="m5 12 5 5 9-10" />
    </svg>
    {{ t('guardrail.rechecked') }}
  </p>
</template>

<style scoped>
.guardrail { align-self: flex-start; max-width: 100%; display: flex; flex-direction: column; gap: 6px; }
.badge {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  max-width: 100%;
  min-height: 32px;
  padding: 4px 12px;
  border: 1px solid transparent;
  border-radius: 999px;
  background: var(--pp-warn-bg);
  color: var(--pp-warn-ink);
  font: inherit;
  font-size: 13px;
  font-weight: 600;
  text-align: start;
  cursor: pointer;
}
.badge:hover { border-color: var(--pp-warn-ink); }
.icon, .chevron { flex: none; }
.chevron { transition: transform 0.15s; }
.chevron.open { transform: rotate(180deg); }
.popover {
  padding: 12px 14px;
  border: 1px solid var(--pp-border-strong);
  border-radius: 12px;
  background: var(--pp-surface);
  box-shadow: var(--pp-shadow-lift);
  font-size: 13px;
}
.popover p { margin: 0; }
.intro { color: var(--pp-muted); }
.list { margin: 8px 0; }
.hint { color: var(--pp-muted); font-size: 12.5px; }
.rechecked {
  align-self: flex-start;
  margin: 0;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12.5px;
  font-weight: 600;
  color: var(--pp-success-ink);
}
</style>
