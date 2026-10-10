<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import type { MaintenancePreview } from '../api/types'

defineProps<{ preview: MaintenancePreview }>()
const { t } = useI18n()
</script>

<template>
  <dl class="facts">
    <dt>{{ t('actions.maintenance.unit') }}</dt>
    <dd><bdi>{{ preview.unitCode }}</bdi> · <span dir="auto">{{ preview.building }}</span></dd>
    <dt>{{ t('actions.maintenance.category') }}</dt>
    <dd>{{ t(`actions.maintenance.categories.${preview.category}`) }}</dd>
    <dt>{{ t('actions.maintenance.priority') }}</dt>
    <dd>{{ t(`actions.maintenance.priorities.${preview.priority}`) }}</dd>
    <dt>{{ t('actions.maintenance.description') }}</dt>
    <dd class="description" dir="auto">{{ preview.description }}</dd>
  </dl>
</template>

<style scoped>
.description { white-space: pre-wrap; }
</style>
