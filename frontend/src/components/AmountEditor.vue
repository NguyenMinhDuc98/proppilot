<script setup lang="ts">
import { computed, onMounted, ref, shallowRef, useId, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { previewAction } from '../api/actions'
import { ApiError } from '../api/http'
import type { PaymentAction, PaymentEdit, PaymentPreviewView } from '../api/types'
import { useActionErrors } from '../composables/actionErrors'
import { formatMoney } from '../composables/format'
import { parseAmount, type PaymentDraft } from '../composables/paymentEdit'

const props = defineProps<{ action: PaymentAction; disabled: boolean }>()
const emit = defineEmits<{ change: [draft: PaymentDraft]; cancel: [] }>()

const { t } = useI18n()
const { describe } = useActionErrors()
const inputId = `amount-${useId()}`
const messageId = `amount-note-${useId()}`
const field = ref<HTMLInputElement | null>(null)

const proposed: PaymentEdit = { amount: props.action.args.amount, overridePayment: props.action.args.overridePayment }

const text = ref(String(proposed.amount))
const allow = ref(proposed.overridePayment)
/** The amount and choice that the diff on display was worked out for. */
const applied = ref<PaymentEdit>({ ...proposed })
const view = shallowRef<PaymentPreviewView>(props.action)
/** The amount the server last refused as more than the balance, while overpayment was not allowed. */
const refused = ref<number | null>(null)
const error = ref('')
const busy = ref(false)

const typed = computed(() => parseAmount(text.value))
const changed = computed(
  () => applied.value.amount !== proposed.amount || applied.value.overridePayment !== proposed.overridePayment,
)
const ready = computed(
  () =>
    typed.value === applied.value.amount &&
    allow.value === applied.value.overridePayment &&
    refused.value === null &&
    !busy.value,
)
const money = (amount: number) => t('actions.money', { amount: formatMoney(amount) })

const draft = computed<PaymentDraft>(() => ({
  edit: changed.value ? { ...applied.value } : null,
  preview: refused.value === null ? view.value : null,
  ready: ready.value,
}))
watch(draft, (current) => emit('change', current), { immediate: true })

async function update() {
  const amount = typed.value
  if (amount === null) {
    error.value = t('actions.editor.invalid')
    return
  }
  busy.value = true
  error.value = ''
  const overridePayment = allow.value
  try {
    view.value = await previewAction(props.action.id, { amount, overridePayment })
    // The choice only means something while the amount really is above the balance; below it, it is cleared so a
    // later, larger amount has to be allowed again.
    allow.value = overridePayment && view.value.preview.overpaymentCredit > 0
    applied.value = { amount, overridePayment: allow.value }
    refused.value = null
  } catch (e) {
    if (e instanceof ApiError && e.code === 'amount_exceeds_outstanding') refused.value = amount
    else error.value = describe(e)
  } finally {
    busy.value = false
  }
}

// Ticking the box is the answer to the warning, so it works out the diff for it straight away.
function setAllow(checked: boolean) {
  allow.value = checked
  if (typed.value !== null) void update()
}

watch(text, () => (error.value = ''))
// The editor opens from a button, so keyboard and screen reader users are taken into it.
onMounted(() => field.value?.focus())
</script>

<template>
  <form class="editor" @submit.prevent="update">
    <label :for="inputId">{{ t('actions.editor.amountLabel') }}</label>
    <div class="row">
      <input
        :id="inputId"
        ref="field"
        v-model="text"
        type="text"
        inputmode="decimal"
        autocomplete="off"
        dir="ltr"
        :disabled="disabled"
        :aria-invalid="error !== ''"
        :aria-describedby="messageId"
      />
      <button type="submit" class="pp-button" :disabled="disabled || busy">{{ t('actions.editor.update') }}</button>
      <button type="button" class="pp-button quiet" :disabled="disabled" @click="emit('cancel')">
        {{ t('actions.cancel') }}
      </button>
    </div>

    <div :id="messageId">
      <p v-if="error" class="error" role="alert">{{ error }}</p>
      <p v-else-if="refused === null && !ready" class="hint">{{ t('actions.editor.dirty') }}</p>
    </div>

    <div v-if="refused !== null" class="warning" role="alert">
      <strong>{{ t('actions.editor.exceedsTitle') }}</strong>
      <i18n-t keypath="actions.editor.exceeds" tag="p">
        <template #amount><bdi>{{ money(refused) }}</bdi></template>
      </i18n-t>
    </div>

    <label v-if="refused !== null || allow" class="check">
      <input
        type="checkbox"
        :checked="allow"
        :disabled="disabled || busy"
        @change="setAllow(($event.target as HTMLInputElement).checked)"
      />
      {{ t('actions.editor.allowOverpayment') }}
    </label>
  </form>
</template>

<style scoped>
.editor { display: flex; flex-direction: column; gap: 8px; padding: 12px; border: 1px solid var(--pp-border); border-radius: 12px; background: var(--pp-surface-2); }
label { font-size: 13px; font-weight: 600; }
.row { display: flex; flex-wrap: wrap; gap: 8px; }
input[type='text'] {
  flex: 1 1 140px;
  min-width: 0;
  min-height: 40px;
  padding: 0 12px;
  border: 1px solid var(--pp-border-strong);
  border-radius: 10px;
  background: var(--pp-surface);
  color: var(--pp-text);
  font: inherit;
  font-variant-numeric: tabular-nums;
  text-align: start;
}
input[aria-invalid='true'] { border-color: var(--pp-danger-ink); }
.hint { margin: 0; font-size: 12.5px; color: var(--pp-muted); }
.error { margin: 0; padding: 6px 10px; border-radius: 8px; background: var(--pp-danger-bg); color: var(--pp-danger-ink); font-size: 13px; }
.warning { padding: 10px 12px; border-radius: 10px; background: var(--pp-warn-bg); color: var(--pp-warn-ink); font-size: 13px; }
.warning p { margin: 4px 0 0; }
.check { display: flex; align-items: center; gap: 10px; min-height: 44px; font-size: 14px; }
.check input { width: 20px; height: 20px; accent-color: var(--pp-primary); }
@media (max-width: 800px) {
  input[type='text'] { min-height: 44px; }
}
</style>
