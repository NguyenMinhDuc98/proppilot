<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { ReminderPreview } from '../api/types'

const props = defineProps<{ preview: ReminderPreview }>()
const { t, locale } = useI18n()

const lang = computed(() => props.preview.language.toLowerCase())
const direction = computed(() => (props.preview.language === 'AR' ? 'rtl' : 'ltr'))
</script>

<template>
  <div class="reminder">
    <dl class="facts">
      <dt>{{ t('actions.reminder.recipient') }}</dt>
      <dd>
        <span dir="auto">{{ locale === 'ar' ? preview.tenantAr : preview.tenantEn }}</span>
        · <bdi>{{ preview.unitCode }}</bdi>
      </dd>
      <dt>{{ t('actions.reminder.purpose') }}</dt>
      <dd>{{ t(`actions.reminder.purposes.${preview.purpose}`) }}</dd>
      <dt>{{ t('actions.reminder.language') }}</dt>
      <dd>{{ t(`actions.reminder.languages.${preview.language}`) }}</dd>
    </dl>

    <!-- The message is in its own language, which may differ from the language of the page. -->
    <div class="message">
      <p class="field">{{ t('actions.reminder.subject') }}</p>
      <p class="subject" :dir="direction" :lang="lang">{{ preview.subject }}</p>
      <p class="field">{{ t('actions.reminder.message') }}</p>
      <p class="body" :dir="direction" :lang="lang">{{ preview.body }}</p>
    </div>

    <p class="outbox" role="note">{{ t('actions.reminder.outbox') }}</p>
  </div>
</template>

<style scoped>
.reminder { display: flex; flex-direction: column; gap: 12px; }
.message {
  padding: 12px 14px;
  border: 1px solid var(--pp-border);
  border-radius: 12px;
  background: var(--pp-surface-2);
  font-size: 14px;
}
.field { margin: 0 0 2px; font-size: 12px; font-weight: 600; color: var(--pp-muted); }
.subject { margin: 0 0 10px; font-weight: 700; overflow-wrap: anywhere; }
.body { margin: 0; white-space: pre-wrap; overflow-wrap: anywhere; }
.outbox { margin: 0; padding: 6px 12px; border-radius: 10px; background: var(--pp-primary-soft); color: var(--pp-primary-ink); font-size: 13px; font-weight: 600; }
</style>
