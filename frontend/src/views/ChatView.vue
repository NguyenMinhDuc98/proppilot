<script setup lang="ts">
import { Delete, Position, VideoPause } from '@element-plus/icons-vue'
import { ElButton, ElInput } from 'element-plus'
import { computed, nextTick, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import MessageBubble from '../components/MessageBubble.vue'
import { useChatStore } from '../stores/chat'

const MAX_LENGTH = 500

const { t, tm, rt } = useI18n()
const chat = useChatStore()
const draft = ref('')
const scroller = ref<HTMLElement | null>(null)

const examples = computed(() => (tm('chat.examples') as string[]).map((e) => rt(e)))

async function submit(text = draft.value) {
  if (!text.trim() || chat.busy) return
  draft.value = ''
  await chat.send(text)
}

function onEnter(event: Event | KeyboardEvent) {
  if ((event as KeyboardEvent).shiftKey) return
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
  <div class="page chat">
    <h1>{{ t('chat.title') }}</h1>
    <p class="subtitle">{{ t('chat.subtitle') }}</p>

    <div ref="scroller" class="thread card" aria-live="polite">
      <div v-if="!chat.hasMessages" class="empty">
        <p>{{ t('chat.examplesTitle') }}</p>
        <div class="examples">
          <button v-for="example in examples" :key="example" class="example" @click="submit(example)">
            {{ example }}
          </button>
        </div>
      </div>
      <MessageBubble v-for="message in chat.messages" :key="message.id" :message="message" />
    </div>

    <div class="composer card">
      <ElInput
        v-model="draft"
        type="textarea"
        :autosize="{ minRows: 1, maxRows: 5 }"
        :maxlength="MAX_LENGTH"
        :placeholder="t('chat.placeholder')"
        resize="none"
        @keydown.enter="onEnter"
      />
      <div class="actions">
        <ElButton v-if="chat.hasMessages" text :icon="Delete" @click="chat.clear()">{{ t('chat.clear') }}</ElButton>
        <span class="spacer" />
        <small class="count">{{ t('chat.charCount', { count: draft.length, max: MAX_LENGTH }) }}</small>
        <ElButton v-if="chat.busy" :icon="VideoPause" @click="chat.stop()">{{ t('chat.stop') }}</ElButton>
        <ElButton v-else type="primary" :disabled="!draft.trim()" @click="submit()">
          {{ t('chat.send') }}
          <el-icon class="mirror-rtl" style="margin-inline-start: 6px"><Position /></el-icon>
        </ElButton>
      </div>
    </div>
  </div>
</template>

<style scoped>
.thread {
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-height: 320px;
  max-height: calc(100vh - 360px);
  overflow-y: auto;
}
.empty { margin: auto; text-align: center; color: var(--pp-muted); }
.examples { display: grid; gap: 8px; grid-template-columns: repeat(auto-fit, minmax(240px, 1fr)); }
.example {
  padding: 10px 12px;
  text-align: start;
  font: inherit;
  color: var(--pp-text);
  background: var(--pp-bg);
  border: 1px solid var(--pp-border);
  border-radius: 10px;
  cursor: pointer;
}
.example:hover { border-color: var(--pp-primary); }
.composer { margin-top: 12px; }
.actions { display: flex; align-items: center; gap: 8px; margin-top: 8px; }
.spacer { flex: 1; }
.count { color: var(--pp-muted); }
</style>
