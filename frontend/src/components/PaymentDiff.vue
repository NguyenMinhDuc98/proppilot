<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import type { PaymentPreview } from '../api/types'
import { formatDate, formatMoney, formatMonth } from '../composables/format'

defineProps<{ preview: PaymentPreview }>()
const { t, locale } = useI18n()

const money = (amount: number) => t('actions.money', { amount: formatMoney(amount) })
</script>

<template>
  <div class="diff">
    <dl class="facts">
      <dt>{{ t('actions.payment.tenant') }}</dt>
      <dd dir="auto">{{ locale === 'ar' ? preview.tenantAr : preview.tenantEn }}</dd>
      <dt>{{ t('actions.payment.unit') }}</dt>
      <dd><bdi>{{ preview.unitCode }}</bdi></dd>
      <dt>{{ t('actions.payment.amount') }}</dt>
      <dd><bdi>{{ money(preview.amount) }}</bdi></dd>
      <dt>{{ t('actions.payment.received') }}</dt>
      <dd>
        <bdi>{{ formatDate(preview.paymentDate, locale) }}</bdi>
        · {{ t(`actions.payment.methods.${preview.method}`) }}
      </dd>
    </dl>

    <div v-if="preview.payments.length" class="scroll" tabindex="0" role="region" :aria-label="t('actions.payment.table')">
      <table>
        <caption>{{ t('actions.payment.currencyNote') }}</caption>
        <thead>
          <tr>
            <th scope="col">{{ t('actions.payment.month') }}</th>
            <th scope="col">{{ t('actions.payment.due') }}</th>
            <th scope="col">{{ t('actions.payment.paidBefore') }}</th>
            <th scope="col">{{ t('actions.payment.paidAfter') }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in preview.payments" :key="row.paymentId">
            <th scope="row">{{ formatMonth(row.period, locale) }}</th>
            <td><bdi>{{ formatMoney(row.amount) }}</bdi></td>
            <td><bdi>{{ formatMoney(row.paidBefore) }}</bdi></td>
            <td class="after"><bdi>{{ formatMoney(row.paidAfter) }}</bdi></td>
          </tr>
        </tbody>
      </table>
    </div>

    <p class="outstanding">
      <span class="label">{{ t('actions.payment.outstanding') }}</span>
      <bdi>{{ money(preview.outstandingBefore) }}</bdi>
      <svg class="mirror-rtl" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M5 12h14M13 6l6 6-6 6" /></svg>
      <strong><bdi>{{ money(preview.outstandingAfter) }}</bdi></strong>
    </p>

    <p v-if="preview.overpaymentCredit > 0" class="credit" role="note">
      <strong>{{ t('actions.payment.credit') }}: <bdi>{{ money(preview.overpaymentCredit) }}</bdi>.</strong>
      {{ t('actions.payment.creditNote') }}
    </p>
  </div>
</template>

<style scoped>
.diff { display: flex; flex-direction: column; gap: 12px; }
.scroll {
  overflow-x: auto;
  border: 1px solid var(--pp-border);
  border-radius: 12px;
  background: var(--pp-surface-2);
}
table { width: 100%; border-collapse: collapse; font-size: 13.5px; font-variant-numeric: tabular-nums; }
caption { padding: 8px 12px 0; text-align: start; font-size: 12px; color: var(--pp-muted); }
th, td { padding: 8px 12px; text-align: start; white-space: nowrap; }
thead th { font-size: 12px; font-weight: 600; color: var(--pp-muted); border-bottom: 1px solid var(--pp-border); vertical-align: bottom; }
tbody th { font-weight: 600; }
tbody tr + tr > * { border-top: 1px solid var(--pp-border); }
.after { font-weight: 700; }
.outstanding { margin: 0; display: flex; flex-wrap: wrap; align-items: center; gap: 4px 8px; font-size: 14px; font-variant-numeric: tabular-nums; }
.label { color: var(--pp-muted); }
.credit { margin: 0; padding: 8px 12px; border-radius: 10px; background: var(--pp-warn-bg); color: var(--pp-warn-ink); font-size: 13px; }
@media (max-width: 800px) {
  /* Four columns have to fit a phone without sideways scrolling, so the headers may wrap. */
  th, td { padding: 8px 6px; }
  thead th { white-space: normal; }
}
</style>
