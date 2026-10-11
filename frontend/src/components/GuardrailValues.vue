<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { UnverifiedValue } from '../api/types'

const props = defineProps<{ values: UnverifiedValue[] }>()
const { t, te } = useI18n()

/** The same value is listed once, however often the answer wrote it. */
const listed = computed(() => {
  const seen = new Set<string>()
  return props.values.filter((item) => {
    const key = `${item.kind}:${item.value}`
    if (seen.has(key)) return false
    seen.add(key)
    return true
  })
})

const kindLabel = (kind: string) => (te(`guardrail.kinds.${kind}`) ? t(`guardrail.kinds.${kind}`) : kind)
</script>

<template>
  <ul class="values">
    <li v-for="item in listed" :key="`${item.kind}:${item.value}`">
      <span class="kind">{{ kindLabel(item.kind) }}</span>
      <!-- Codes and amounts keep their order inside right-to-left text. -->
      <bdi class="value">{{ item.value }}</bdi>
    </li>
  </ul>
</template>

<style scoped>
.values { margin: 0; padding: 0; list-style: none; display: flex; flex-direction: column; gap: 4px; }
.values li { display: flex; flex-wrap: wrap; align-items: baseline; gap: 2px 10px; }
.kind { min-width: 7em; color: var(--pp-muted); font-size: 12px; }
.value { font-weight: 700; font-variant-numeric: tabular-nums; overflow-wrap: anywhere; }
</style>
