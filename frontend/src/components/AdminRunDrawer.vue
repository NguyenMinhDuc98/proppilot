<script setup lang="ts">
import { ElDrawer } from 'element-plus'
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { AdminRun, AdminRunDetail } from '../api/types'
import { formatDateTime } from '../composables/format'
import { adminToolDetails, buildStoredTrace } from '../composables/trace'
import GuardrailValues from './GuardrailValues.vue'
import TraceTimeline from './TraceTimeline.vue'

const props = defineProps<{
  /** The row that was opened: what is known of the run while its detail is still on its way. */
  run: AdminRun | null
  detail: AdminRunDetail | null
  loading: boolean
  failed: boolean
}>()
const open = defineModel<boolean>({ required: true })
const { t, locale } = useI18n()

/** The drawer comes in from the end of the line: the right edge in English, the left edge in Arabic. */
const direction = computed(() => (locale.value === 'ar' ? 'ltr' : 'rtl'))
const trace = computed(() => (props.detail ? buildStoredTrace(props.detail, adminToolDetails(props.detail)) : null))
const question = computed(() => props.detail?.question ?? props.run?.question ?? '')
</script>

<template>
  <ElDrawer v-model="open" append-to-body destroy-on-close :direction="direction" size="min(560px, 100vw)">
    <template #header="{ titleId, titleClass }">
      <h2 :id="titleId" :class="titleClass">{{ t('admin.detail.title') }}</h2>
    </template>

    <div v-if="run" class="content">
      <dl class="facts">
        <dt>{{ t('admin.detail.time') }}</dt>
        <dd><time :datetime="run.createdAt">{{ formatDateTime(run.createdAt, locale) }}</time></dd>
        <dt>{{ t('admin.detail.status') }}</dt>
        <dd>{{ t(`admin.statuses.${run.status}`) }}</dd>
        <dt>{{ t('admin.detail.runId') }}</dt>
        <dd><bdi class="mono">{{ run.id }}</bdi></dd>
      </dl>

      <section>
        <h3>{{ t('admin.detail.question') }}</h3>
        <blockquote class="question" dir="auto">{{ question }}</blockquote>
      </section>

      <p v-if="loading" class="muted" role="status">{{ t('admin.detail.loading') }}</p>
      <p v-else-if="failed" class="problem" role="alert">{{ t('admin.detail.error') }}</p>

      <template v-else-if="detail && trace">
        <TraceTimeline :trace="trace" />

        <section>
          <h3>{{ t('admin.detail.values') }}</h3>
          <p v-if="!detail.guardrailFindings.length" class="muted">{{ t('admin.detail.noValues') }}</p>
          <GuardrailValues v-else :values="detail.guardrailFindings" />
        </section>
      </template>
    </div>
  </ElDrawer>
</template>

<style scoped>
h2 { margin: 0; font-size: 16px; font-weight: 700; }
h3 { margin: 0 0 6px; font-size: 13px; color: var(--pp-muted); font-weight: 600; }
.content { display: flex; flex-direction: column; gap: 18px; }
.mono { font-family: ui-monospace, 'SF Mono', Menlo, Consolas, monospace; font-size: 12.5px; }
.question {
  margin: 0;
  padding: 12px 14px;
  border-radius: 12px;
  border-inline-start: 3px solid var(--pp-primary);
  background: var(--pp-bg);
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.muted { margin: 0; color: var(--pp-muted); }
.problem { margin: 0; padding: 8px 12px; border-radius: 10px; background: var(--pp-danger-bg); color: var(--pp-danger-ink); }
</style>
