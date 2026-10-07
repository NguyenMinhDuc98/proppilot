<script setup lang="ts">
import { ElOption, ElButton, ElInputNumber, ElPagination, ElSelect, ElTable, ElTableColumn, ElTag } from 'element-plus'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { fetchUnits } from '../api/units'
import type { Unit } from '../api/types'

const PAGE_SIZE = 15
const CITIES = ['Riyadh', 'Jeddah', 'Dammam']
const BUILDINGS = ['A', 'B', 'C', 'D', 'E', 'F']
const STATUSES = ['VACANT', 'OCCUPIED', 'MAINTENANCE']
const STATUS_TAG: Record<string, 'success' | 'info' | 'warning'> = {
  VACANT: 'success',
  OCCUPIED: 'info',
  MAINTENANCE: 'warning',
}

const { t, locale } = useI18n()
const money = new Intl.NumberFormat('en-US')

const filters = reactive({
  city: '' as string,
  building: '' as string,
  status: '' as string,
  bedrooms: undefined as number | undefined,
  minRent: undefined as number | undefined,
  maxRent: undefined as number | undefined,
})
const page = ref(1)
const total = ref(0)
const units = ref<Unit[]>([])
const loading = ref(false)
const failed = ref(false)

const hasFilters = computed(() => Object.values(filters).some((v) => v !== '' && v !== undefined && v !== null))

async function load() {
  loading.value = true
  failed.value = false
  try {
    const result = await fetchUnits({
      city: filters.city || undefined,
      building: filters.building || undefined,
      status: filters.status || undefined,
      bedrooms: filters.bedrooms ?? undefined,
      minRent: filters.minRent ?? undefined,
      maxRent: filters.maxRent ?? undefined,
      page: page.value - 1,
      size: PAGE_SIZE,
    })
    units.value = result.items
    total.value = result.total
  } catch {
    failed.value = true
    units.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function reset() {
  Object.assign(filters, { city: '', building: '', status: '', bedrooms: undefined, minRent: undefined, maxRent: undefined })
}

watch(filters, () => {
  page.value = 1
  void load()
})
watch(page, load)
onMounted(load)
</script>

<template>
  <div class="page">
    <h1>{{ t('units.title') }}</h1>

    <div class="card filters">
      <label>
        <span>{{ t('units.city') }}</span>
        <ElSelect v-model="filters.city" clearable :placeholder="t('units.all')">
          <ElOption v-for="c in CITIES" :key="c" :label="t(`units.cities.${c}`)" :value="c" />
        </ElSelect>
      </label>
      <label>
        <span>{{ t('units.building') }}</span>
        <ElSelect v-model="filters.building" clearable :placeholder="t('units.all')">
          <ElOption v-for="b in BUILDINGS" :key="b" :label="b" :value="b" />
        </ElSelect>
      </label>
      <label>
        <span>{{ t('units.status') }}</span>
        <ElSelect v-model="filters.status" clearable :placeholder="t('units.all')">
          <ElOption v-for="s in STATUSES" :key="s" :label="t(`units.statuses.${s}`)" :value="s" />
        </ElSelect>
      </label>
      <label>
        <span>{{ t('units.bedrooms') }}</span>
        <ElInputNumber v-model="filters.bedrooms" :min="1" :max="4" controls-position="right" />
      </label>
      <label>
        <span>{{ t('units.minRent') }}</span>
        <ElInputNumber v-model="filters.minRent" :min="0" :step="500" controls-position="right" />
      </label>
      <label>
        <span>{{ t('units.maxRent') }}</span>
        <ElInputNumber v-model="filters.maxRent" :min="0" :step="500" controls-position="right" />
      </label>
      <ElButton v-if="hasFilters" text @click="reset">{{ t('units.reset') }}</ElButton>
    </div>

    <div class="card table">
      <ElTable v-loading="loading" :data="units" :empty-text="failed ? t('chat.errorNetwork') : t('units.empty')">
        <ElTableColumn :label="t('units.code')" prop="code" min-width="90" />
        <ElTableColumn :label="t('units.building')" min-width="150">
          <template #default="{ row }">{{ row.building }} ({{ row.buildingCode }})</template>
        </ElTableColumn>
        <ElTableColumn :label="t('units.city')" min-width="90">
          <template #default="{ row }">{{ locale === 'ar' ? row.cityAr : row.cityEn }}</template>
        </ElTableColumn>
        <ElTableColumn :label="t('units.floor')" prop="floor" width="80" />
        <ElTableColumn :label="t('units.bedrooms')" prop="bedrooms" width="100" />
        <ElTableColumn :label="t('units.area')" prop="areaSqm" width="110" />
        <ElTableColumn :label="t('units.rent')" min-width="130">
          <template #default="{ row }">{{ money.format(row.monthlyRent) }}</template>
        </ElTableColumn>
        <ElTableColumn :label="t('units.status')" min-width="110">
          <template #default="{ row }">
            <ElTag :type="STATUS_TAG[row.status]" size="small">{{ t(`units.statuses.${row.status}`) }}</ElTag>
          </template>
        </ElTableColumn>
      </ElTable>
      <div class="pager">
        <span class="muted">{{ t('units.total', { n: total }) }}</span>
        <ElPagination v-model:current-page="page" :page-size="PAGE_SIZE" :total="total" layout="prev, pager, next" background />
      </div>
    </div>
  </div>
</template>

<style scoped>
.filters { display: flex; flex-wrap: wrap; gap: 12px; align-items: flex-end; margin-bottom: 12px; }
.filters label { display: flex; flex-direction: column; gap: 4px; font-size: 12px; color: var(--pp-muted); }
.filters :deep(.el-select), .filters :deep(.el-input-number) { width: 150px; }
.table { padding: 0; overflow: hidden; }
.pager { display: flex; justify-content: space-between; align-items: center; padding: 12px 16px; }
.muted { color: var(--pp-muted); }
</style>
