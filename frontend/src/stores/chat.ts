import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { newIdempotencyKey } from '../api/actions'
import { streamChat, streamConfirmation } from '../api/chat'
import { ApiError } from '../api/http'
import type { ActionView, ChatEvent, DonePayload, ErrorCode, HistoryTurn } from '../api/types'
import { useAppInfo } from '../composables/useAppInfo'

export interface ToolChip {
  name: string
  args: Record<string, unknown>
  status: 'running' | 'done' | 'failed'
  summary?: string
}

export interface ChatMessage {
  id: number
  role: 'user' | 'assistant'
  /** The answer. For an assistant message that is only the text after its last tool call. */
  text: string
  /** What the model wrote before calling tools ("Let me check..."), kept out of the answer and shown in the trace. */
  preamble?: string
  tools: ToolChip[]
  /** Changes this answer proposed. Each waits for a person to approve or reject it. */
  actions: ActionView[]
  usage?: DonePayload
  error?: string
  /** Set when the server reported the failure; the UI shows localised text for it instead of `error`. */
  errorCode?: ErrorCode
  streaming: boolean
}

/** The server's proppilot.chat.max-history-items default, assumed until /api/info has reported the real limit. */
const DEFAULT_MAX_HISTORY_ITEMS = 20

/** The decision of a card that is on its way to the server. */
export type Decision = 'approving' | 'rejecting'

/** A decision is final, so it is the only kind the assistant is asked to confirm. */
const DECIDED: ReadonlySet<ActionView['status']> = new Set(['APPROVED', 'REJECTED', 'FAILED'])

export const useChatStore = defineStore('chat', () => {
  const { info } = useAppInfo()
  const messages = ref<ChatMessage[]>([])
  const busy = ref(false)
  let nextId = 1
  let controller: AbortController | null = null
  /** Actions whose decision the assistant has been asked to confirm, so that never happens twice. */
  const confirmed = new Set<string>()
  /** Decisions taken while a reply was streaming; they are confirmed one by one once it has ended. */
  const waiting: string[] = []
  /**
   * Decisions on their way, by action id. A card is mounted anew when the chat is left and entered again, so what
   * it must not do twice or at once is kept here rather than in the card.
   */
  const deciding = ref<Record<string, Decision>>({})
  /** One key for every approval attempt of an action, so a retry from any card of it can only replay the first. */
  const approvalKeys = new Map<string, string>()

  const hasMessages = computed(() => messages.value.length > 0)

  /**
   * Only exchanges that completed successfully, so a failed or half-streamed answer never pollutes the next question.
   * Capped to what the server accepts, dropping the oldest whole exchanges so a question never loses its answer.
   * The confirmation of a decision follows an answer, not a question, so it is never part of an exchange.
   */
  function history(): HistoryTurn[] {
    const exchanges: HistoryTurn[][] = []
    for (let i = 0; i + 1 < messages.value.length; i++) {
      const question = messages.value[i]
      const answer = messages.value[i + 1]
      if (question.role !== 'user' || answer.streaming || answer.error || answer.text.trim() === '') continue
      exchanges.push([
        { role: 'user', text: question.text },
        { role: 'assistant', text: answer.text },
      ])
    }
    // Not slice(-maxExchanges): that would keep every exchange when the limit is below 2.
    const maxExchanges = Math.floor((info.value?.maxHistoryItems ?? DEFAULT_MAX_HISTORY_ITEMS) / 2)
    return exchanges.slice(Math.max(0, exchanges.length - maxExchanges)).flat()
  }

  /** The server emits tool calls only after a model round has finished, so the text so far was not part of the answer. */
  function moveTextToPreamble(message: ChatMessage) {
    const note = message.text.trim()
    if (note) message.preamble = message.preamble ? `${message.preamble}\n\n${note}` : note
    message.text = ''
  }

  function apply(message: ChatMessage, event: ChatEvent) {
    switch (event.type) {
      case 'tool_call':
        moveTextToPreamble(message)
        message.tools.push({ name: event.name, args: event.args, status: 'running' })
        break
      case 'tool_result': {
        const chip = [...message.tools].reverse().find((t) => t.name === event.name && t.status === 'running')
        if (chip) {
          chip.status = event.error ? 'failed' : 'done'
          chip.summary = event.summary
        }
        break
      }
      case 'action_proposed': {
        const { type: _type, ...proposed } = event
        message.actions.push(proposed)
        break
      }
      case 'token':
        message.text += event.text
        break
      case 'error':
        message.error = event.message
        message.errorCode = event.code
        break
      case 'done': {
        const { type: _type, ...usage } = event
        message.usage = usage
        break
      }
    }
  }

  /** Adds a message and returns it as the store holds it, so that later changes to it are seen by the UI. */
  function append(role: ChatMessage['role'], text = '', streaming = false): ChatMessage {
    messages.value.push({ id: nextId++, role, text, tools: [], actions: [], streaming })
    return messages.value[messages.value.length - 1]
  }

  /** Streams one reply into `answer`; a stream that fails is reported to `onFailure`, one the person stopped is not. */
  async function stream(
    answer: ChatMessage,
    open: (signal: AbortSignal) => AsyncGenerator<ChatEvent>,
    onFailure: (failure: unknown) => void,
  ) {
    busy.value = true
    controller = new AbortController()
    try {
      for await (const event of open(controller.signal)) {
        apply(answer, event)
      }
    } catch (e) {
      if (!(e instanceof DOMException && e.name === 'AbortError')) onFailure(e)
    } finally {
      answer.streaming = false
      busy.value = false
      controller = null
    }
  }

  async function send(question: string) {
    const text = question.trim()
    if (!text || busy.value) return

    const past = history()
    append('user', text)
    const answer = append('assistant', '', true)
    await stream(answer, (signal) => streamChat(text, past, signal), (failure) => {
      answer.error = failure instanceof ApiError ? failure.message : 'network'
    })
    await confirmWaiting()
  }

  /** The reply has no question above it: the server writes that turn from the stored decision. */
  async function confirm(actionId: string) {
    const past = history()
    const reply = append('assistant', '', true)
    await stream(reply, (signal) => streamConfirmation(actionId, past, signal), () => {
      // The card already shows the outcome, so a reply that never started is dropped rather than reported.
      messages.value = messages.value.filter((message) => message !== reply)
    })
  }

  async function confirmWaiting() {
    while (!busy.value && waiting.length > 0) {
      const [actionId] = waiting.splice(0, 1)
      await confirm(actionId)
    }
  }

  /**
   * Puts the server's latest word on a proposal into its card. A decision that has just been taken is then confirmed
   * by the assistant, once, after any reply that is streaming now.
   */
  function settleAction(view: ActionView) {
    const holder = messages.value.find((message) => message.actions.some((action) => action.id === view.id))
    if (!holder) return
    holder.actions = holder.actions.map((action) => (action.id === view.id ? view : action))
    if (DECIDED.has(view.status) && view.confirmedAt === null && !confirmed.has(view.id)) {
      confirmed.add(view.id)
      waiting.push(view.id)
      void confirmWaiting()
    }
  }

  function beginDecision(actionId: string, decision: Decision) {
    deciding.value[actionId] = decision
  }

  function endDecision(actionId: string) {
    delete deciding.value[actionId]
  }

  /**
   * An action can be approved only once, so a double click, a retry after a lost response or a second card of it
   * can only ever replay the first outcome, never make the change twice.
   */
  function approvalKey(actionId: string): string {
    const key = approvalKeys.get(actionId) ?? newIdempotencyKey()
    approvalKeys.set(actionId, key)
    return key
  }

  function stop() {
    controller?.abort()
  }

  function clear() {
    stop()
    waiting.length = 0
    approvalKeys.clear()
    messages.value = []
  }

  return {
    messages, busy, hasMessages, deciding, send, settleAction, beginDecision, endDecision, approvalKey, stop, clear,
  }
})
