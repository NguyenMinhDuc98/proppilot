<script setup lang="ts">
import { ElDrawer } from 'element-plus'
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { LeaseSource } from '../api/types'
import { clauseBody, clauseOf, directionOf, pdfLink, textRuns } from '../composables/clause'

const props = defineProps<{ source: LeaseSource | null }>()
const open = defineModel<boolean>({ required: true })
const { t, locale } = useI18n()

/** The drawer comes in from the end of the line: the right edge in English, the left edge in Arabic. */
const direction = computed(() => (locale.value === 'ar' ? 'ltr' : 'rtl'))
const tenant = computed(() => (locale.value === 'ar' ? props.source?.tenantNameAr : props.source?.tenantNameEn))
const clause = computed(() => (props.source ? clauseOf(props.source) : null))
/** The document is read in its own language and direction, whatever the language of the app. */
const textDir = computed(() => (props.source ? directionOf(props.source.language) : 'ltr'))
const runs = computed(() => (props.source ? textRuns(clauseBody(props.source)) : []))
const pdf = computed(() => (props.source ? pdfLink(props.source) : null))

/**
 * Element Plus updates the model only once the drawer has finished leaving, so a chip clicked in the meantime would be
 * answered by the drawer closing again. Whatever asks for it to close, the model follows at once.
 */
function close(done: () => void) {
  done()
  open.value = false
}
</script>

<template>
  <ElDrawer v-model="open" append-to-body :direction="direction" size="min(460px, 100vw)" :before-close="close">
    <template #header="{ titleId, titleClass }">
      <h2 :id="titleId" :class="titleClass" :dir="textDir" :lang="source?.language">
        {{ source?.documentTitle }}
      </h2>
    </template>

    <div v-if="source" class="content">
      <dl class="facts">
        <dt>{{ t('lease.unit') }}</dt>
        <dd><bdi>{{ source.unitCode }}</bdi></dd>
        <dt>{{ t('lease.tenant') }}</dt>
        <dd dir="auto">{{ tenant }}</dd>
        <dt>{{ t('lease.clause') }}</dt>
        <dd>
          <template v-if="clause === null">{{ t('lease.preamble') }}</template>
          <template v-else>
            <bdi>{{ clause }}</bdi>
            <template v-if="source.sectionTitle">
              · <span :dir="textDir" :lang="source.language">{{ source.sectionTitle }}</span>
            </template>
          </template>
        </dd>
        <dt>{{ t('lease.page') }}</dt>
        <dd><bdi>{{ source.page }}</bdi></dd>
      </dl>

      <blockquote class="text" :dir="textDir" :lang="source.language">
        <template v-for="(run, i) in runs" :key="i">
          <bdi v-if="run.isolated">{{ run.text }}</bdi>
          <template v-else>{{ run.text }}</template>
        </template>
      </blockquote>
    </div>

    <template v-if="pdf" #footer>
      <a class="pp-button primary pdf" :href="pdf" target="_blank" rel="noopener">
        {{ t('lease.openPdf') }}
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M14 4h6v6M20 4l-9 9M18 14v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h5" /></svg>
      </a>
    </template>
  </ElDrawer>
</template>

<style scoped>
h2 { margin: 0; font-size: 16px; font-weight: 700; line-height: 1.4; overflow-wrap: anywhere; }
.content { display: flex; flex-direction: column; gap: 18px; }
.text {
  margin: 0;
  padding: 14px 16px;
  border-radius: 12px;
  border-inline-start: 3px solid var(--pp-primary);
  background: var(--pp-bg);
  line-height: 1.75;
  overflow-wrap: anywhere;
}
.pdf { width: 100%; text-decoration: none; }
</style>
