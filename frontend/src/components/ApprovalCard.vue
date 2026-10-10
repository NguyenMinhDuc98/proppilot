<script setup lang="ts">
import { computed, nextTick, ref, useId, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { approveAction, fetchAction, rejectAction } from '../api/actions'
import { ApiError } from '../api/http'
import type { ActionState, ActionView } from '../api/types'
import { useActionErrors } from '../composables/actionErrors'
import { formatCountdown } from '../composables/format'
import type { PaymentDraft } from '../composables/paymentEdit'
import { useCountdown } from '../composables/useCountdown'
import { useChatStore, type Decision } from '../stores/chat'
import ActionKindIcon from './ActionKindIcon.vue'
import ActionOutcome from './ActionOutcome.vue'
import ActionStatusPill from './ActionStatusPill.vue'
import AmountEditor from './AmountEditor.vue'
import MaintenanceDetails from './MaintenanceDetails.vue'
import PaymentDiff from './PaymentDiff.vue'
import ReminderDetails from './ReminderDetails.vue'

const props = defineProps<{ action: ActionView }>()
const { t, locale } = useI18n()
const chat = useChatStore()
const { describe } = useActionErrors()
const titleId = `approval-${useId()}`

const mode = ref<'view' | 'edit' | 'reject'>('view')
const reason = ref('')
const reasonField = ref<HTMLTextAreaElement | null>(null)
const error = ref('')
/** The server answered that this proposal does not exist (the demo data was reset), so there is nothing to decide. */
const gone = ref(false)
const draft = ref<PaymentDraft | null>(null)

/** The decision on its way to the server, if any. Everything is locked while it is, in every card of the action. */
const phase = computed(() => chat.deciding[props.action.id])
const open = computed(() => props.action.status === 'PROPOSED')
/** Still to be decided by the person looking at it: nobody else has settled it and it has not been deleted. */
const waiting = computed(() => open.value && !gone.value)
const busy = computed(() => phase.value !== undefined)
const state = computed<ActionState>(() => {
  if (phase.value === 'approving') return 'APPROVING'
  if (phase.value === 'rejecting') return 'REJECTING'
  return props.action.status
})
const canApprove = computed(() => draft.value?.ready ?? true)
const approved = computed(() => props.action.status === 'APPROVED')
const showDetails = computed(() => open.value || approved.value)

/**
 * The diff of a payment that is waiting: the one for the amount being edited, or the proposal's own. Null while it is
 * not known. An approved payment shows none: it was worked out against the invoices as they were when it was
 * proposed, and approval works from the invoices as they are, so its outcome is what the card says instead.
 */
const paymentPreview = computed(() => {
  if (props.action.kind !== 'RECORD_PAYMENT' || !open.value) return null
  return draft.value ? (draft.value.preview?.preview ?? null) : props.action.preview
})
/** A reminder is written again when it is approved, so an approved card shows the message that was stored. */
const reminder = computed(() => {
  const a = props.action
  if (a.kind !== 'SEND_TENANT_REMINDER') return null
  if (open.value) return a.preview
  return approved.value && a.result && 'messageId' in a.result ? a.result : null
})
const summary = computed(() => {
  const source = draft.value?.preview ?? props.action
  return locale.value === 'ar' ? source.summaryAr : source.summaryEn
})
const announcement = computed(() => (state.value === 'PROPOSED' ? '' : t(`actions.announce.${state.value}`)))

const { remainingMs } = useCountdown(() => props.action.expiresAt, () => open.value)

/** The server expires a proposal lazily, so once the time is up it is asked what became of it. */
watch(
  () => open.value && remainingMs.value === 0,
  (due) => {
    if (due) void reread()
  },
  { immediate: true },
)

// An edit that was never sent must not outlive the proposal it was for: once somebody else has decided it, or it has
// expired or is gone, the card shows the proposal as the server has it.
watch(waiting, (stillWaiting) => {
  if (!stillWaiting) {
    mode.value = 'view'
    draft.value = null
  }
})

// An edit belongs to the editor: leaving it, for the reject form or a decision, leaves the proposal as it was.
watch(mode, async (current) => {
  if (current !== 'edit') draft.value = null
  if (current === 'reject') {
    await nextTick()
    reasonField.value?.focus()
  }
})

async function reread() {
  try {
    chat.settleAction(await fetchAction(props.action.id))
  } catch {
    // Nothing to show instead: the card keeps what it has, and the next decision gets the server's answer.
  }
}

async function decide(next: Decision, call: () => Promise<ActionView>) {
  if (busy.value) return
  const { id } = props.action
  chat.beginDecision(id, next)
  error.value = ''
  try {
    chat.settleAction(await call())
    mode.value = 'view'
  } catch (e) {
    error.value = describe(e)
    if (e instanceof ApiError && e.status === 404) {
      gone.value = true
    } else if (e instanceof ApiError && [409, 410, 422].includes(e.status)) {
      // The proposal is no longer what the card shows (decided, expired or failed), so the card is brought up to date.
      await reread()
    }
  } finally {
    chat.endDecision(id)
  }
}

const approve = () =>
  decide('approving', async () => {
    const { id } = props.action
    const edit = draft.value?.edit ?? undefined
    return (await approveAction(id, chat.approvalKey(id), edit)).action
  })

const reject = () => decide('rejecting', () => rejectAction(props.action.id, reason.value.trim() || undefined))
</script>

<template>
  <div class="approval" :class="action.status.toLowerCase()" role="group" :aria-labelledby="titleId" :aria-busy="busy">
    <header class="head">
      <span class="icon"><ActionKindIcon :kind="action.kind" /></span>
      <h3 :id="titleId">{{ t(`actions.kinds.${action.kind}`) }}</h3>
      <ActionStatusPill :state="state" />
    </header>

    <p class="summary" dir="auto">{{ summary }}</p>

    <template v-if="showDetails">
      <PaymentDiff v-if="paymentPreview" :preview="paymentPreview" />
      <MaintenanceDetails v-else-if="action.kind === 'CREATE_MAINTENANCE_REQUEST'" :preview="action.preview" />
      <ReminderDetails v-else-if="reminder" :preview="reminder" />
    </template>

    <ActionOutcome v-if="!open" :action="action" />

    <template v-if="waiting">
      <p class="expiry" role="timer">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="12" cy="12" r="9" /><path d="M12 7v5l3 2" /></svg>
        <i18n-t v-if="remainingMs > 0" keypath="actions.expiresIn" tag="span">
          <template #time><bdi>{{ formatCountdown(remainingMs) }}</bdi></template>
        </i18n-t>
        <span v-else>{{ t('actions.expiringNow') }}</span>
      </p>

      <AmountEditor
        v-if="mode === 'edit' && action.kind === 'RECORD_PAYMENT'"
        :action="action"
        :disabled="busy"
        @change="draft = $event"
        @cancel="mode = 'view'"
      />

      <div v-if="mode === 'reject'" class="reject">
        <label :for="`${titleId}-reason`">{{ t('actions.reasonLabel') }}</label>
        <textarea
          :id="`${titleId}-reason`"
          ref="reasonField"
          v-model="reason"
          rows="2"
          maxlength="200"
          dir="auto"
          :disabled="busy"
          :placeholder="t('actions.reasonPlaceholder')"
        />
      </div>

      <div class="buttons">
        <template v-if="mode === 'reject'">
          <button type="button" class="pp-button primary" :disabled="busy" @click="reject">
            {{ phase === 'rejecting' ? t('actions.rejecting') : t('actions.confirmReject') }}
          </button>
          <button type="button" class="pp-button quiet" :disabled="busy" @click="mode = 'view'">
            {{ t('actions.cancel') }}
          </button>
        </template>
        <template v-else>
          <button type="button" class="pp-button primary" :disabled="busy || !canApprove" @click="approve">
            {{ phase === 'approving' ? t('actions.approving') : t('actions.approve') }}
          </button>
          <button type="button" class="pp-button" :disabled="busy" @click="mode = 'reject'">
            {{ t('actions.reject') }}
          </button>
          <button
            v-if="action.kind === 'RECORD_PAYMENT' && mode === 'view'"
            type="button"
            class="pp-button quiet"
            :disabled="busy"
            @click="mode = 'edit'"
          >
            {{ t('actions.editAmount') }}
          </button>
        </template>
      </div>
    </template>

    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <span class="sr-only" role="status" aria-live="polite">{{ announcement }}</span>
  </div>
</template>

<style scoped>
.approval {
  --tone: var(--pp-primary);
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-width: 0;
  padding: 14px 16px;
  background: var(--pp-surface);
  border: 1px solid var(--pp-border);
  border-inline-start: 4px solid var(--tone);
  border-radius: 14px;
  box-shadow: var(--pp-shadow);
}
.approved { --tone: var(--pp-success-ink); }
.failed { --tone: var(--pp-danger-ink); }
.expired { --tone: var(--pp-warn-ink); }
.rejected { --tone: var(--pp-muted); }

.head { display: flex; align-items: center; gap: 10px; }
.icon { flex: none; display: grid; place-items: center; width: 34px; height: 34px; border-radius: 10px; background: var(--pp-primary-soft); color: var(--pp-primary-ink); }
.head h3 { flex: 1; min-width: 0; margin: 0; font-size: 15px; font-weight: 700; }
.summary { margin: 0; overflow-wrap: anywhere; }
.expiry { margin: 0; display: flex; align-items: center; gap: 6px; font-size: 12.5px; color: var(--pp-muted); font-variant-numeric: tabular-nums; }
.reject { display: flex; flex-direction: column; gap: 6px; }
.reject label { font-size: 13px; font-weight: 600; }
.reject textarea {
  width: 100%;
  padding: 8px 12px;
  border: 1px solid var(--pp-border-strong);
  border-radius: 10px;
  background: var(--pp-surface);
  color: var(--pp-text);
  font: inherit;
  resize: vertical;
}
.buttons { display: flex; flex-wrap: wrap; gap: 8px; }
.error { margin: 0; padding: 8px 12px; border-radius: 10px; background: var(--pp-danger-bg); color: var(--pp-danger-ink); font-size: 13.5px; }
@media (max-width: 800px) {
  .approval { padding: 12px; }
  .reject textarea { min-height: 64px; }
}
</style>
