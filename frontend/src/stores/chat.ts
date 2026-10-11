import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { newIdempotencyKey } from '../api/actions'
import { streamChat, streamConfirmation } from '../api/chat'
import { ApiError } from '../api/http'
import type {
  ActionView, ChatEvent, DonePayload, ErrorCode, GuardrailStatus, HistoryTurn, LeaseSource, UnverifiedValue,
} from '../api/types'
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
  /** The lease clauses its searches returned, each once. A citation in the answer is a chip only for one of these. */
  sources: LeaseSource[]
  usage?: DonePayload
  /**
   * What the grounding guardrail found in the answer: the values it could not match to the data, and its verdict.
   * Absent when it did not check the answer. The verdict is `FLAGGED` from the moment it found values, and the
   * server's own word (`RETRIED_FLAGGED` for an answer given twice) once the run is done.
   */
  guardrail?: { status: GuardrailStatus; values: UnverifiedValue[] }
  /**
   * While the assistant answers again: the note the answer it replaces had before its tools, as it was when the text
   * was dropped. It is put back when the new answer never completes and the earlier one is sent again.
   */
  replaced?: { preamble?: string }
  error?: string
  /** Set when the server reported the failure; the UI shows localised text for it instead of `error`. */
  errorCode?: ErrorCode
  streaming: boolean
}

/** The server's proppilot.chat.max-history-items default, assumed until /api/info has reported the real limit. */
const DEFAULT_MAX_HISTORY_ITEMS = 20

/** The server's proppilot.chat.max-history-turn-length default, assumed until /api/info has reported the real limit. */
const DEFAULT_MAX_HISTORY_TURN_LENGTH = 4000

const isHighSurrogate = (code: number) => code >= 0xd800 && code <= 0xdbff

/**
 * An answer that quotes lease clauses can be longer than a history turn may be, and a turn the server refuses would
 * fail every later question. What a follow-up needs is the beginning of the answer, so the text is cut, at the end of
 * a line when there is one in its second half, rather than the exchange dropped. The cut is marked with an ellipsis.
 */
function fitTurn(text: string, maxLength: number): string {
  if (text.length <= maxLength) return text
  const room = maxLength - 1 // the ellipsis takes one
  const lineEnd = text.lastIndexOf('\n', room)
  if (lineEnd > maxLength / 2) return `${text.slice(0, lineEnd).trimEnd()}…`
  // Half of a character that takes two code units is not text a server has to accept.
  const end = isHighSurrogate(text.charCodeAt(room - 1)) ? room - 1 : room
  return `${text.slice(0, end).trimEnd()}…`
}

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
   * Capped to what the server accepts: the oldest whole exchanges are dropped so a question never loses its answer,
   * and an answer longer than a turn may be is cut.
   * The confirmation of a decision follows an answer, not a question, so it is never part of an exchange.
   */
  function history(): HistoryTurn[] {
    const maxTurnLength = info.value?.maxHistoryTurnLength ?? DEFAULT_MAX_HISTORY_TURN_LENGTH
    const exchanges: HistoryTurn[][] = []
    for (let i = 0; i + 1 < messages.value.length; i++) {
      const question = messages.value[i]
      const answer = messages.value[i + 1]
      if (question.role !== 'user' || answer.streaming || answer.error || answer.text.trim() === '') continue
      exchanges.push([
        { role: 'user', text: question.text },
        { role: 'assistant', text: fitTurn(answer.text, maxTurnLength) },
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
        addSources(message, event.sources)
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
      case 'revise':
        revise(message)
        break
      case 'error':
        message.error = event.message
        message.errorCode = event.code
        break
      case 'guardrail':
        message.guardrail = { status: 'FLAGGED', values: event.values }
        break
      case 'done': {
        const { type: _type, ...usage } = event
        message.usage = usage
        if (usage.guardrailStatus) {
          message.guardrail = { status: usage.guardrailStatus, values: message.guardrail?.values ?? [] }
        }
        break
      }
    }
  }

  /**
   * The assistant was asked to answer again, so what was streamed so far is not the answer. Its tools, the clauses
   * they returned and the note before them are what the run did, and the new answer may cite those clauses without
   * searching again, so they stay. When the new answer never completes the server says so with a second revise and
   * sends the earlier answer again, which then has the note it had: what the dropped attempt wrote before its own
   * tools is dropped with it.
   */
  function revise(message: ChatMessage) {
    if (message.replaced) {
      message.preamble = message.replaced.preamble
    } else {
      message.replaced = { preamble: message.preamble }
    }
    message.text = ''
  }

  /** A clause that two searches both return is one source. */
  function addSources(message: ChatMessage, found: LeaseSource[] = []) {
    for (const source of found) {
      if (!message.sources.some((known) => known.ref === source.ref)) message.sources.push(source)
    }
  }

  /** Adds a message and returns it as the store holds it, so that later changes to it are seen by the UI. */
  function append(role: ChatMessage['role'], text = '', streaming = false): ChatMessage {
    messages.value.push({ id: nextId++, role, text, tools: [], actions: [], sources: [], streaming })
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
      answer.replaced = undefined
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
