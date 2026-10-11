<script setup lang="ts">
import { onMounted, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ADMIN_PAGE_SIZE, fetchAdminRun, fetchAdminRuns } from '../api/admin'
import { ApiError } from '../api/http'
import type { AdminRun, AdminRunDetail, RunStatus } from '../api/types'
import { fetchStats } from '../api/units'
import AdminRunDrawer from '../components/AdminRunDrawer.vue'
import AdminRunRow from '../components/AdminRunRow.vue'
import { useAdminToken } from '../composables/adminToken'

const STATUSES: RunStatus[] = ['OK', 'ERROR', 'MAX_ITERATIONS', 'TRUNCATED', 'ABORTED']

const { t } = useI18n()
const { token, remember, forget } = useAdminToken()

/** `locked` asks for the token, `disabled` is a server that has none configured, `failed` a list that did not load. */
const phase = ref<'locked' | 'loading' | 'ready' | 'disabled' | 'failed'>(token.value ? 'loading' : 'locked')
const draft = ref('')
const rejected = ref(false)

const filters = reactive({ status: '' as RunStatus | '', model: '', flaggedOnly: false })
const models = ref<string[]>([])
const runs = ref<AdminRun[]>([])
const hasMore = ref(false)
const loadingMore = ref(false)
const moreFailed = ref(false)
let latestList = 0

const selected = ref<AdminRun | null>(null)
const detail = ref<AdminRunDetail | null>(null)
const detailLoading = ref(false)
const detailFailed = ref(false)
const detailOpen = ref(false)
let latestDetail = 0

const failedWith = (failure: unknown, status: number) => failure instanceof ApiError && failure.status === status

/**
 * The token is forgotten and with it what it fetched: the runs, the one that was opened, and any request still on
 * its way, whose answer would otherwise put the visitors' questions back on a page that is no longer unlocked.
 */
function leave(to: 'locked' | 'disabled') {
  forget()
  latestList++
  latestDetail++
  runs.value = []
  hasMore.value = false
  loadingMore.value = false
  moreFailed.value = false
  selected.value = null
  detail.value = null
  detailLoading.value = false
  detailFailed.value = false
  detailOpen.value = false
  phase.value = to
}

/** The server did not accept the token: it is dropped, and the form asks for another. */
function tokenRefused(failure: unknown): boolean {
  if (!failedWith(failure, 403)) return false
  rejected.value = true
  leave('locked')
  return true
}

/** A server with no admin token configured answers 404 to every admin endpoint, as if they did not exist. */
function adminDisabled(failure: unknown): boolean {
  if (!failedWith(failure, 404)) return false
  leave('disabled')
  return true
}

/** The first page for the current filters, or the page older than the last run listed. */
async function load(older = false) {
  const read = ++latestList
  const before = older ? runs.value.at(-1)?.createdAt : undefined
  moreFailed.value = false
  if (older) loadingMore.value = true
  else phase.value = 'loading'
  try {
    const page = await fetchAdminRuns(
      token.value,
      { status: filters.status || undefined, model: filters.model || undefined, flaggedOnly: filters.flaggedOnly },
      before,
    )
    if (read !== latestList) return
    runs.value = older ? [...runs.value, ...page] : page
    hasMore.value = page.length === ADMIN_PAGE_SIZE
    phase.value = 'ready'
  } catch (failure) {
    if (read !== latestList || tokenRefused(failure) || adminDisabled(failure)) return
    if (older) moreFailed.value = true
    else phase.value = 'failed'
  } finally {
    if (read === latestList) loadingMore.value = false
  }
}

/** The models the server has runs for, to filter by; a list that cannot be had leaves only "all models". */
async function loadModels() {
  try {
    models.value = (await fetchStats()).byModel?.map((usage) => usage.model) ?? []
  } catch {
    models.value = []
  }
}

function unlock() {
  const value = draft.value.trim()
  if (!value) return
  draft.value = ''
  rejected.value = false
  remember(value)
  void start()
}

function lock() {
  leave('locked')
}

async function start() {
  void loadModels()
  await load()
}

async function openRun(run: AdminRun) {
  const read = ++latestDetail
  selected.value = run
  detail.value = null
  detailFailed.value = false
  detailLoading.value = true
  detailOpen.value = true
  try {
    const found = await fetchAdminRun(token.value, run.id)
    if (read === latestDetail) detail.value = found
  } catch (failure) {
    if (read !== latestDetail || tokenRefused(failure)) return
    detailFailed.value = true
  } finally {
    if (read === latestDetail) detailLoading.value = false
  }
}

// A filter that changes asks for the list again from its start, even while a list is on its way: the answer of the
// request it replaces is dropped. The filters are shown only while there is a token to ask with.
watch(
  () => [filters.status, filters.model, filters.flaggedOnly],
  () => {
    if (phase.value !== 'locked' && phase.value !== 'disabled') void load()
  },
)

onMounted(() => {
  if (token.value) void start()
})
</script>

<template>
  <div class="page">
    <div class="head">
      <div>
        <h1>{{ t('admin.title') }}</h1>
        <p class="subtitle">{{ t('admin.subtitle') }}</p>
      </div>
      <button v-if="phase === 'ready' || phase === 'failed'" type="button" class="pp-button quiet" @click="lock">
        {{ t('admin.lock') }}
      </button>
    </div>

    <p v-if="phase === 'disabled'" class="card problem" role="alert">{{ t('admin.disabled') }}</p>

    <form v-else-if="phase === 'locked'" class="card unlock" @submit.prevent="unlock">
      <label for="admin-token">{{ t('admin.tokenLabel') }}</label>
      <input
        id="admin-token"
        v-model="draft"
        type="password"
        autocomplete="off"
        autocapitalize="off"
        spellcheck="false"
        :aria-invalid="rejected"
        :aria-describedby="rejected ? 'admin-token-error' : 'admin-token-hint'"
      />
      <p id="admin-token-hint" class="hint">{{ t('admin.tokenHint') }}</p>
      <p v-if="rejected" id="admin-token-error" class="error" role="alert">{{ t('admin.rejected') }}</p>
      <button type="submit" class="pp-button primary" :disabled="!draft.trim()">{{ t('admin.unlock') }}</button>
    </form>

    <template v-else>
      <form class="filters" :aria-label="t('admin.filters.label')" @submit.prevent>
        <label>
          <span>{{ t('admin.filters.status') }}</span>
          <select v-model="filters.status">
            <option value="">{{ t('admin.filters.allStatuses') }}</option>
            <option v-for="status in STATUSES" :key="status" :value="status">{{ t(`admin.statuses.${status}`) }}</option>
          </select>
        </label>
        <label>
          <span>{{ t('admin.filters.model') }}</span>
          <select v-model="filters.model">
            <option value="">{{ t('admin.filters.allModels') }}</option>
            <option v-for="model in models" :key="model" :value="model">{{ model }}</option>
          </select>
        </label>
        <label class="flagged">
          <input v-model="filters.flaggedOnly" type="checkbox" />
          <span>{{ t('admin.filters.flaggedOnly') }}</span>
        </label>
      </form>

      <p v-if="phase === 'loading'" class="muted" role="status">{{ t('admin.loading') }}</p>

      <div v-else-if="phase === 'failed'" class="card problem" role="alert">
        <p>{{ t('admin.error') }}</p>
        <button type="button" class="pp-button" @click="load()">{{ t('admin.retry') }}</button>
      </div>

      <p v-else-if="!runs.length" class="card muted">{{ t('admin.empty') }}</p>

      <template v-else>
        <ul class="list">
          <AdminRunRow v-for="run in runs" :key="run.id" :run="run" @open="openRun(run)" />
        </ul>

        <div v-if="moreFailed" class="problem" role="alert">{{ t('admin.error') }}</div>
        <button v-if="hasMore" type="button" class="pp-button more" :disabled="loadingMore" @click="load(true)">
          {{ loadingMore ? t('admin.loadingOlder') : t('admin.loadOlder') }}
        </button>
      </template>
    </template>

    <AdminRunDrawer v-model="detailOpen" :run="selected" :detail="detail" :loading="detailLoading" :failed="detailFailed" />
  </div>
</template>

<style scoped>
.head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; }
.head .subtitle { margin-bottom: 18px; }
.head .pp-button { flex: none; white-space: nowrap; }
.unlock { display: flex; flex-direction: column; align-items: flex-start; gap: 8px; max-width: 420px; }
.unlock label, .filters label > span { font-size: 13px; font-weight: 600; }
input[type='password'], select {
  width: 100%;
  min-height: 44px;
  padding: 0 12px;
  border: 1px solid var(--pp-border-strong);
  border-radius: 12px;
  background: var(--pp-surface);
  color: var(--pp-text);
  font: inherit;
}
.hint { margin: 0; font-size: 12.5px; color: var(--pp-muted); }
.error { margin: 0; padding: 6px 12px; border-radius: 10px; background: var(--pp-danger-bg); color: var(--pp-danger-ink); font-size: 13px; }
.filters { display: flex; flex-wrap: wrap; align-items: flex-end; gap: 12px 16px; margin-bottom: 16px; }
.filters label { display: flex; flex-direction: column; gap: 4px; min-width: 0; flex: 1 1 160px; }
.filters .flagged { flex-direction: row; align-items: center; gap: 8px; min-height: 44px; flex: 0 1 auto; }
.filters .flagged input { width: 18px; height: 18px; }
.list { margin: 0; padding: 0; display: flex; flex-direction: column; gap: 10px; }
.more { align-self: center; margin: 16px auto 0; display: flex; }
.muted { color: var(--pp-muted); }
.problem { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 12px; color: var(--pp-danger-ink); background: var(--pp-danger-bg); }
.problem p { margin: 0; }
div.problem { margin-top: 12px; padding: 8px 12px; border-radius: 10px; }
</style>
