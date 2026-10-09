import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { streamChat } from '../api/chat'
import { ApiError } from '../api/http'
import type { ChatEvent, DonePayload, ErrorCode, HistoryTurn } from '../api/types'

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
  usage?: DonePayload
  error?: string
  /** Set when the server reported the failure; the UI shows localised text for it instead of `error`. */
  errorCode?: ErrorCode
  streaming: boolean
}

export const useChatStore = defineStore('chat', () => {
  const messages = ref<ChatMessage[]>([])
  const busy = ref(false)
  let nextId = 1
  let controller: AbortController | null = null

  const hasMessages = computed(() => messages.value.length > 0)

  /** Only exchanges that completed successfully, so a failed or half-streamed answer never pollutes the next question. */
  function history(): HistoryTurn[] {
    const turns: HistoryTurn[] = []
    for (let i = 0; i + 1 < messages.value.length; i += 2) {
      const question = messages.value[i]
      const answer = messages.value[i + 1]
      if (answer.streaming || answer.error || answer.text.trim() === '') continue
      turns.push({ role: 'user', text: question.text }, { role: 'assistant', text: answer.text })
    }
    return turns
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

  async function send(question: string) {
    const text = question.trim()
    if (!text || busy.value) return

    const past = history()
    messages.value.push({ id: nextId++, role: 'user', text, tools: [], streaming: false })
    messages.value.push({ id: nextId++, role: 'assistant', text: '', tools: [], streaming: true })
    const answer = messages.value[messages.value.length - 1]

    busy.value = true
    controller = new AbortController()
    try {
      for await (const event of streamChat(text, past, controller.signal)) {
        apply(answer, event)
      }
    } catch (e) {
      if (!(e instanceof DOMException && e.name === 'AbortError')) {
        answer.error = e instanceof ApiError ? e.message : 'network'
      }
    } finally {
      answer.streaming = false
      busy.value = false
      controller = null
    }
  }

  function stop() {
    controller?.abort()
  }

  function clear() {
    stop()
    messages.value = []
  }

  return { messages, busy, hasMessages, send, stop, clear }
})
