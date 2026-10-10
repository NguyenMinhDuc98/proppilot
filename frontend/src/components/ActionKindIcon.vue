<script setup lang="ts">
import type { ActionKind } from '../api/types'

withDefaults(defineProps<{ kind: ActionKind; size?: number }>(), { size: 20 })

const PATHS: Record<ActionKind, string> = {
  RECORD_PAYMENT: 'M2 6h20v12H2zM2 10h20M6 15h4',
  CREATE_MAINTENANCE_REQUEST:
    'M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z',
  SEND_TENANT_REMINDER: 'M3 5h18v14H3zM3 7l9 6 9-6',
}
</script>

<template>
  <svg :width="size" :height="size" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
    <path :d="PATHS[kind]" />
  </svg>
</template>
