<script setup lang="ts">
import { useId } from 'vue'
import { useI18n } from 'vue-i18n'
import type { LeaseSource } from '../api/types'
import CitationChip from './CitationChip.vue'

defineProps<{ sources: LeaseSource[] }>()
const emit = defineEmits<{ open: [source: LeaseSource, trigger: HTMLElement] }>()
const { t } = useI18n()
const labelId = `citations-${useId()}`
</script>

<template>
  <div class="citations" role="group" :aria-labelledby="labelId">
    <span :id="labelId" class="label">{{ t('lease.sources', sources.length) }}</span>
    <CitationChip
      v-for="source in sources"
      :key="source.ref"
      :source="source"
      @open="(clause, trigger) => emit('open', clause, trigger)"
    />
  </div>
</template>

<style scoped>
.citations { display: flex; flex-wrap: wrap; align-items: center; gap: 6px 4px; }
.label { margin-inline-end: 4px; font-size: 12.5px; font-weight: 600; color: var(--pp-muted); }
</style>
