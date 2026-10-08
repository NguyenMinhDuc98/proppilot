<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import MessageBubble from '../components/MessageBubble.vue'
import { formatModel } from '../composables/format'
import { useChatStore } from '../stores/chat'

const MAX_LENGTH = 500

const { t, tm, rt } = useI18n()
const chat = useChatStore()
const draft = ref('')
const scroller = ref<HTMLElement | null>(null)
const input = ref<HTMLTextAreaElement | null>(null)

const examples = computed(() => (tm('chat.examples') as string[]).map((e) => rt(e)))

/** The model behind the latest answer, shown as a small status pill once we know it. */
const modelLabel = computed(() => {
  const latest = [...chat.messages].reverse().find((m) => m.usage)?.usage
  if (!latest) return ''
  return latest.provider === 'offline' ? t('chat.offlineAssistant') : formatModel(latest.model)
})

function grow() {
  const el = input.value
  if (!el) return
  el.style.height = 'auto'
  el.style.height = `${Math.min(el.scrollHeight, 160)}px`
}

async function submit(text = draft.value) {
  if (!text.trim() || chat.busy) return
  draft.value = ''
  await nextTick()
  grow()
  await chat.send(text)
}

function onEnter(event: KeyboardEvent) {
  if (event.shiftKey || event.isComposing) return
  event.preventDefault()
  void submit()
}

// Keep the newest content in view while the answer streams.
watch(
  () => chat.messages.map((m) => m.text.length + m.tools.length).join(),
  async () => {
    await nextTick()
    scroller.value?.scrollTo({ top: scroller.value.scrollHeight })
  },
)
</script>

<template>
  <section class="chat">
    <header class="head">
      <h1>{{ t('chat.title') }}</h1>
      <span v-if="modelLabel" class="pill"><span class="dot" />{{ modelLabel }}</span>
      <button v-if="chat.hasMessages" type="button" class="ghost" @click="chat.clear()">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M3 6h18M8 6V4h8v2M19 6l-1 14H6L5 6" /></svg>
        {{ t('chat.clear') }}
      </button>
    </header>

    <div ref="scroller" class="thread" aria-live="polite">
      <div class="thread-inner">
        <div v-if="!chat.hasMessages" class="welcome">
          <p class="lead">{{ t('chat.subtitle') }}</p>
          <p class="eyebrow">{{ t('chat.examplesTitle') }}</p>
          <div class="examples">
            <button v-for="example in examples" :key="example" type="button" class="example" @click="submit(example)">
              {{ example }}
            </button>
          </div>
        </div>
        <MessageBubble v-for="message in chat.messages" :key="message.id" :message="message" />
      </div>
    </div>

    <form class="composer-wrap" @submit.prevent="submit()">
      <div class="composer">
        <label for="question" class="sr-only">{{ t('chat.placeholder') }}</label>
        <textarea
          id="question"
          ref="input"
          v-model="draft"
          rows="1"
          :maxlength="MAX_LENGTH"
          :placeholder="t('chat.placeholder')"
          @keydown.enter="onEnter"
          @input="grow"
        />
        <div class="row">
          <span class="count">{{ t('chat.charCount', { count: draft.length, max: MAX_LENGTH }) }}</span>
          <button v-if="chat.busy" type="button" class="btn secondary" @click="chat.stop()">{{ t('chat.stop') }}</button>
          <button v-else type="submit" class="btn primary" :disabled="!draft.trim()">
            {{ t('chat.send') }}
            <svg class="mirror-rtl" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M22 2 11 13M22 2l-7 20-4-9-9-4z" /></svg>
          </button>
        </div>
      </div>
    </form>
  </section>
</template>

<style scoped>
.chat {
  display: flex;
  flex-direction: column;
  height: 100vh;
  height: 100dvh;
}
.head {
  flex: none;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 16px 40px;
  border-bottom: 1px solid var(--pp-border);
}
.head h1 { margin: 0; flex: 1; font-size: 18px; font-weight: 700; }
.pill {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 5px 12px;
  border-radius: 999px;
  background: var(--pp-surface);
  border: 1px solid var(--pp-border);
  color: var(--pp-muted);
  font-size: 12px;
  font-weight: 600;
}
.dot { width: 8px; height: 8px; border-radius: 50%; background: var(--pp-success-ink); }
.ghost {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-height: 36px;
  padding: 0 12px;
  border: 0;
  border-radius: 10px;
  background: transparent;
  color: var(--pp-muted);
  font: inherit;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
}
.ghost:hover { background: var(--pp-primary-soft); color: var(--pp-primary-ink); }

.thread { flex: 1; min-height: 0; overflow-y: auto; }
.thread-inner {
  display: flex;
  flex-direction: column;
  gap: 24px;
  max-width: 800px;
  margin-inline: auto;
  padding: 32px 24px 16px;
}
.welcome { padding-top: 6vh; }
.lead { margin: 0 0 28px; color: var(--pp-muted); font-size: 16px; max-width: 560px; }
.eyebrow { margin: 0 0 12px; font-size: 12px; font-weight: 700; letter-spacing: 0.08em; text-transform: uppercase; color: var(--pp-muted); }
.examples { display: grid; grid-template-columns: repeat(auto-fit, minmax(260px, 1fr)); gap: 12px; }
.example {
  padding: 14px 16px;
  text-align: start;
  font: inherit;
  font-weight: 500;
  color: var(--pp-text);
  background: var(--pp-surface);
  border: 1px solid var(--pp-border);
  border-radius: 14px;
  box-shadow: var(--pp-shadow);
  cursor: pointer;
  transition: border-color 0.15s, transform 0.15s;
}
.example:hover { border-color: var(--pp-primary); transform: translateY(-1px); }

.composer-wrap { flex: none; max-width: 800px; width: 100%; margin-inline: auto; padding: 8px 24px 24px; }
.composer {
  padding: 14px 14px 12px 20px;
  background: var(--pp-surface);
  border: 1px solid var(--pp-border-strong);
  border-radius: 18px;
  box-shadow: var(--pp-shadow-lift);
}
.composer:focus-within { border-color: var(--pp-primary); }
.composer textarea {
  display: block;
  width: 100%;
  max-height: 160px;
  padding: 6px 0;
  border: 0;
  outline: 0;
  resize: none;
  background: transparent;
  color: var(--pp-text);
  font: inherit;
  line-height: 1.5;
}
.composer textarea::placeholder { color: var(--pp-muted); }
.row { display: flex; align-items: center; gap: 12px; margin-top: 8px; }
.count { flex: 1; font-size: 12px; color: var(--pp-muted); font-variant-numeric: tabular-nums; }
.btn {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  min-height: 40px;
  padding: 0 18px;
  border: 0;
  border-radius: 12px;
  font: inherit;
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
}
.btn.primary { background: var(--pp-primary); color: var(--pp-on-primary); }
.btn.primary:disabled { opacity: 0.45; cursor: not-allowed; }
.btn.secondary { background: var(--pp-bg); color: var(--pp-text); border: 1px solid var(--pp-border-strong); }

@media (max-width: 800px) {
  .chat { height: calc(100vh - var(--topbar-h) - var(--tabbar-h)); height: calc(100dvh - var(--topbar-h) - var(--tabbar-h)); }
  .head { display: none; }
  .thread-inner { padding: 18px 14px 8px; gap: 20px; }
  .welcome { padding-top: 0; }
  .composer-wrap { padding: 6px 12px 10px; }
  .composer { padding: 8px 8px 8px 16px; }
  .composer textarea { min-height: 44px; }
  .btn { min-height: 44px; }
}
</style>
