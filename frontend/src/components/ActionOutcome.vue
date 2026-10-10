<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { ActionView } from '../api/types'
import { useActionErrors } from '../composables/actionErrors'
import { formatMoney } from '../composables/format'

const props = defineProps<{ action: ActionView }>()
const { t } = useI18n()
const { reason } = useActionErrors()

const money = (amount: number) => t('actions.money', { amount: formatMoney(amount) })

const payment = computed(() => {
  const a = props.action
  return a.kind === 'RECORD_PAYMENT' && a.result && 'applied' in a.result ? a.result : null
})
const requestId = computed(() => {
  const a = props.action
  return a.kind === 'CREATE_MAINTENANCE_REQUEST' && a.result && 'requestId' in a.result ? a.result.requestId : null
})
/** Why an approval failed, in the viewer's language; the server's English text is never shown. */
const failure = computed(() => {
  const { result } = props.action
  return result && 'code' in result ? reason(result.code) : reason(undefined)
})
</script>

<template>
  <div class="outcome" :class="action.status.toLowerCase()">
    <template v-if="action.status === 'APPROVED'">
      <template v-if="payment">
        <i18n-t keypath="actions.outcome.payment" tag="p">
          <template #applied><bdi>{{ money(payment.applied) }}</bdi></template>
          <template #outstanding><bdi>{{ money(payment.outstandingAfter) }}</bdi></template>
        </i18n-t>
        <i18n-t v-if="payment.overpaymentCredit > 0" keypath="actions.outcome.credit" tag="p">
          <template #credit><bdi>{{ money(payment.overpaymentCredit) }}</bdi></template>
        </i18n-t>
      </template>
      <i18n-t v-else-if="requestId !== null" keypath="actions.outcome.maintenance" tag="p">
        <template #id><bdi>{{ requestId }}</bdi></template>
      </i18n-t>
      <p v-else-if="action.kind === 'SEND_TENANT_REMINDER'">{{ t('actions.outcome.reminder') }}</p>
      <p v-else>{{ t('actions.outcome.approved') }}</p>
    </template>

    <template v-else-if="action.status === 'REJECTED'">
      <p>{{ t('actions.outcome.rejected') }}</p>
      <p v-if="action.decisionReason" class="reason">
        {{ t('actions.outcome.reason') }}: <q dir="auto">{{ action.decisionReason }}</q>
      </p>
    </template>

    <p v-else-if="action.status === 'EXPIRED'">{{ t('actions.outcome.expired') }}</p>

    <template v-else-if="action.status === 'FAILED'">
      <p>{{ t('actions.outcome.failed') }}</p>
      <p class="reason">{{ failure }}</p>
    </template>
  </div>
</template>

<style scoped>
.outcome { padding: 10px 12px; border-radius: 10px; background: var(--pp-surface-2); font-size: 14px; }
.outcome p { margin: 0; }
.outcome p + p { margin-top: 4px; }
.reason { font-size: 13px; opacity: 0.9; overflow-wrap: anywhere; }
.approved { background: var(--pp-success-bg); color: var(--pp-success-ink); }
.failed { background: var(--pp-danger-bg); color: var(--pp-danger-ink); }
.expired { background: var(--pp-warn-bg); color: var(--pp-warn-ink); }
</style>
