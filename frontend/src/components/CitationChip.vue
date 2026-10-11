<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { LeaseSource } from '../api/types'
import { clauseOf } from '../composables/clause'

const props = defineProps<{ source: LeaseSource }>()
const emit = defineEmits<{ open: [source: LeaseSource, trigger: HTMLElement] }>()
const { t } = useI18n()

const clause = computed(() => clauseOf(props.source))
const name = computed(() =>
  clause.value === null
    ? t('lease.cite.preamble', { unit: props.source.unitCode })
    : t('lease.cite.clause', { unit: props.source.unitCode, clause: clause.value }),
)
</script>

<template>
  <button
    type="button"
    class="cite"
    aria-haspopup="dialog"
    :aria-label="name"
    @click="emit('open', source, $event.currentTarget as HTMLElement)"
  >
    <!-- <bdi> keeps a code in its order inside right-to-left text, and out of the direction that `dir="auto"` reads. -->
    <bdi>{{ source.unitCode }}</bdi>
    <bdi v-if="clause !== null" class="clause">§{{ clause }}</bdi>
    <span v-else class="clause">{{ t('lease.preamble') }}</span>
  </button>
</template>

<style scoped>
.cite {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  min-height: 24px;
  margin-inline: 2px;
  padding: 0 9px;
  border: 1px solid transparent;
  border-radius: 999px;
  background: var(--pp-primary-soft);
  color: var(--pp-primary-ink);
  font: inherit;
  font-size: 12px;
  font-weight: 600;
  line-height: 1.2;
  vertical-align: baseline;
  cursor: pointer;
}
.cite:hover { border-color: var(--pp-primary); }
.clause { font-weight: 500; opacity: 0.85; }

@media (max-width: 800px) {
  .cite { min-height: 28px; }
}
</style>
