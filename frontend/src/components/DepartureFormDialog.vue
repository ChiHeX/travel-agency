<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { adminApi } from '@/api/modules'

/**
 * 团期新增 / 修改表单弹窗（对应契约 POST /admin/departures 与 PUT /admin/departures/{departureId}）。
 *
 * 只提交契约 DepartureUpsertRequest 允许的字段：`status`、`reservedPeople`、`confirmedPeople`
 * 都不在前端提交范围内 —— 新建团期由后端固定为 DRAFT（先上架才能报名），
 * 名额计数由下单 / 支付 / 退款链路维护。提交这些字段会被后端严格模式直接拒绝（400）。
 *
 * 金额按契约 Money 提交十进制字符串（固定 2 位小数）；主键按契约 Id 提交字符串，
 * 避免 JavaScript 大整数精度丢失。页面校验只用于改善交互，最终由后端裁定。
 *
 * 线路 / 导游候选项由本组件分页加载：契约 Size 上限是 100，后端也会把 size 钳到 100，
 * 固定只取第一页会让第 101 条之后的线路永远选不到。
 */
const props = defineProps({
  modelValue: { type: Boolean, default: false },
  departure: { type: Object, default: null }
})
const emit = defineEmits(['update:modelValue', 'saved'])

/** 契约 Size 的取值上限（后端同样按 100 截断）。 */
const OPTION_PAGE_SIZE = 100

const submitting = ref(false)
const submitError = ref('')

const routes = ref([])
const routeTotal = ref(0)
const routePageNo = ref(0)
const routesLoading = ref(false)

const guides = ref([])
const guideTotal = ref(0)
const guidePageNo = ref(0)
const guidesLoading = ref(false)

const hasMoreRoutes = computed(() => routes.value.length < routeTotal.value)
const hasMoreGuides = computed(() => guides.value.length < guideTotal.value)

const form = reactive({
  routeId: '',
  startDate: '',
  endDate: '',
  adultPrice: '',
  childPrice: '',
  maxPeople: 20,
  guideId: ''
})

/** 契约 Money：十进制字符串，固定两位小数。 */
function money(value) {
  if (value === null || value === undefined || value === '') return ''
  const number = Number(value)
  return Number.isFinite(number) ? number.toFixed(2) : ''
}

function reset() {
  const source = props.departure || {}
  Object.assign(form, {
    routeId: source.routeId ? String(source.routeId) : '',
    startDate: source.startDate || '',
    endDate: source.endDate || '',
    adultPrice: money(source.adultPrice),
    childPrice: money(source.childPrice),
    maxPeople: source.maxPeople || 20,
    guideId: source.guideId ? String(source.guideId) : ''
  })
  submitError.value = ''
}

/** 按 id 去重合并，避免"加载更多"把同一页重复追加进来。 */
function mergeById(existing, incoming) {
  const seen = new Set(existing.map((item) => String(item.id)))
  return [...existing, ...incoming.filter((item) => !seen.has(String(item.id)))]
}

/**
 * 加载线路候选项。
 *
 * @param {boolean} reset true 回到第一页并替换；false 追加下一页
 */
async function loadRoutes(reset) {
  if (routesLoading.value) return
  routesLoading.value = true
  try {
    const next = reset ? 1 : routePageNo.value + 1
    const page = await adminApi.routes({ page: next, size: OPTION_PAGE_SIZE })
    const items = page?.items || []
    routes.value = reset ? items : mergeById(routes.value, items)
    routeTotal.value = Number(page?.total ?? routes.value.length)
    routePageNo.value = next
  } catch {
    // 候选项加载失败不阻断表单：编辑时当前线路由 ensureSelectionLoaded 单独取回。
  } finally {
    routesLoading.value = false
  }
}

/** 加载导游候选项（契约没有 keyword 参数，只能按页追加）。 */
async function loadGuides(reset) {
  if (guidesLoading.value) return
  guidesLoading.value = true
  try {
    const next = reset ? 1 : guidePageNo.value + 1
    const page = await adminApi.guides({ page: next, size: OPTION_PAGE_SIZE })
    const items = page?.items || []
    guides.value = reset ? items : mergeById(guides.value, items)
    guideTotal.value = Number(page?.total ?? guides.value.length)
    guidePageNo.value = next
  } catch {
    // 同 loadRoutes。
  } finally {
    guidesLoading.value = false
  }
}

/**
 * 保证当前团期的线路 / 导游出现在候选项里。
 *
 * <p>编辑一条挂在"第 150 条线路"上的团期时，它不在第一页候选项里，下拉会显示成空白，
 * 运营看不到这条团期到底挂在哪个线路 / 导游上。这里按 id 单独取回并置顶。</p>
 */
async function ensureSelectionLoaded() {
  const current = props.departure
  if (!current) return

  const routeId = current.routeId ? String(current.routeId) : ''
  if (routeId && !routes.value.some((item) => String(item.id) === routeId)) {
    try {
      // 契约 GET /admin/routes/{routeId} 返回 { route, departures, itinerary, ... }。
      const detail = await adminApi.route(routeId)
      if (detail?.route) routes.value = [detail.route, ...routes.value]
    } catch {
      // 线路已被删除时保持原样：保存仍会提交原 id，由后端判定。
    }
  }

  const guideId = current.guideId ? String(current.guideId) : ''
  if (guideId && !guides.value.some((item) => String(item.id) === guideId)) {
    try {
      const guide = await adminApi.guide(guideId)
      if (guide) guides.value = [guide, ...guides.value]
    } catch {
      // 同线路。
    }
  }
}

async function loadOptions() {
  routePageNo.value = 0
  guidePageNo.value = 0
  routes.value = []
  guides.value = []
  routeTotal.value = 0
  guideTotal.value = 0
  await Promise.all([loadRoutes(true), loadGuides(true)])
  await ensureSelectionLoaded()
}

watch(() => [props.modelValue, props.departure], () => {
  if (props.modelValue) {
    reset()
    loadOptions()
  }
}, { immediate: true })

function close() {
  emit('update:modelValue', false)
}

/**
 * 页面侧校验：与后端 DepartureUpsertRequest 的约束保持一致（必填、非负、2 位小数、最多 10 位整数）。
 * 目的是让运营在提交前就看到问题，而不是等接口回一个 422。
 */
function validate() {
  if (!form.routeId) return '请选择所属线路'
  if (!form.startDate || !form.endDate) return '请填写出发与返程日期'
  if (form.endDate < form.startDate) return '返程日期不能早于出发日期'

  const prices = [
    ['成人价格', form.adultPrice],
    ['儿童价格', form.childPrice]
  ]
  for (const [label, raw] of prices) {
    const text = String(raw).trim()
    if (!text) return `请填写${label}`
    if (!/^\d{1,10}(\.\d{1,2})?$/.test(text)) return `${label}应为非负数，最多 10 位整数与 2 位小数`
  }

  const maxPeople = Number(form.maxPeople)
  if (!Number.isInteger(maxPeople) || maxPeople < 1) return '最大人数应为大于 0 的整数'
  return ''
}

async function save() {
  if (submitting.value) return
  submitError.value = ''
  const invalid = validate()
  if (invalid) return ElMessage.warning(invalid)

  // 主键按契约 Id 提交字符串；金额提交固定两位小数的字符串。
  const payload = {
    routeId: String(form.routeId),
    startDate: form.startDate,
    endDate: form.endDate,
    adultPrice: money(form.adultPrice),
    childPrice: money(form.childPrice),
    maxPeople: Number(form.maxPeople),
    guideId: form.guideId ? String(form.guideId) : null
  }

  submitting.value = true
  try {
    const saved = props.departure?.id
      ? await adminApi.updateDeparture(props.departure.id, payload)
      : await adminApi.createDeparture(payload)
    ElMessage.success(props.departure?.id
      ? '团期已更新'
      : '团期草稿已创建，请到列表里“开放报名”后才会对外售卖')
    emit('saved', saved)
    close()
  } catch (cause) {
    // 409（名额 / 状态 / 导游冲突）与 422（字段语义）由后端给出可读 message，展示在原地，不重复弹窗。
    submitError.value = cause.message || '保存失败，请稍后重试'
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-dialog
    append-to-body
    class="departure-form-dialog"
    :model-value="modelValue"
    :title="departure?.id ? '编辑团期' : '新增团期'"
    width="min(680px, calc(100vw - 32px))"
    :close-on-click-modal="!submitting"
    :show-close="!submitting"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div class="dialog-form-grid">
      <p v-if="submitError" class="form-error wide" role="alert">{{ submitError }}</p>

      <div class="form-field wide">
        <label>所属线路 <span class="req">*</span></label>
        <select v-model="form.routeId">
          <option value="">请选择线路</option>
          <option v-for="route in routes" :key="route.id" :value="String(route.id)">
            {{ route.name }}（{{ route.departureCity }} → {{ route.destination }}）
          </option>
        </select>
        <p class="option-hint">
          已加载 {{ routes.length }} / 共 {{ routeTotal || routes.length }} 条
          <button
            v-if="hasMoreRoutes"
            type="button"
            class="option-more"
            :disabled="routesLoading"
            @click="loadRoutes(false)"
          >
            {{ routesLoading ? '加载中…' : '加载更多' }}
          </button>
        </p>
      </div>

      <div class="form-field">
        <label>出发日期 <span class="req">*</span></label>
        <input v-model="form.startDate" type="date" />
      </div>

      <div class="form-field">
        <label>返程日期 <span class="req">*</span></label>
        <input v-model="form.endDate" type="date" />
      </div>

      <div class="form-field">
        <label>成人价格 <span class="req">*</span></label>
        <input v-model="form.adultPrice" inputmode="decimal" placeholder="例如：2999.00" />
      </div>

      <div class="form-field">
        <label>儿童价格 <span class="req">*</span></label>
        <input v-model="form.childPrice" inputmode="decimal" placeholder="例如：1999.00" />
      </div>

      <div class="form-field">
        <label>最大人数 <span class="req">*</span></label>
        <input v-model.number="form.maxPeople" type="number" min="1" />
      </div>

      <div class="form-field">
        <label>带团导游</label>
        <select v-model="form.guideId">
          <option value="">暂不分配</option>
          <option v-for="guide in guides" :key="guide.id" :value="String(guide.id)">{{ guide.name }}</option>
        </select>
        <p class="option-hint">
          已加载 {{ guides.length }} / 共 {{ guideTotal || guides.length }} 条
          <button
            v-if="hasMoreGuides"
            type="button"
            class="option-more"
            :disabled="guidesLoading"
            @click="loadGuides(false)"
          >
            {{ guidesLoading ? '加载中…' : '加载更多' }}
          </button>
        </p>
      </div>

      <p class="form-hint wide">
        新建团期为“草稿”状态，需要在列表中改为“报名中”才会对用户开放报名。
        同一导游在同一时间范围内不允许带两个团（后端返回 409）；只有草稿状态的团期可以改挂线路。
      </p>
    </div>

    <template #footer>
      <button class="secondary-button" :disabled="submitting" @click="close">取消</button>
      <button class="primary-button" :disabled="submitting" @click="save">
        {{ submitting ? '保存中…' : '保存团期' }}
      </button>
    </template>
  </el-dialog>
</template>

<style scoped>
.dialog-form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}

.dialog-form-grid .wide {
  grid-column: 1 / -1;
}

.req {
  color: var(--danger-red);
}

.form-hint {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--text-secondary);
}

.option-hint {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--text-tertiary);
}

.option-more {
  padding: 0;
  border: 0;
  background: none;
  font-size: 12px;
  color: var(--brand-primary, #2563eb);
  cursor: pointer;
}

.option-more:disabled {
  color: var(--text-tertiary);
  cursor: default;
}

@media (max-width: 640px) {
  .dialog-form-grid {
    grid-template-columns: 1fr;
  }
}
</style>

<style>
@media (max-width: 640px) {
  .departure-form-dialog { margin-top: 5dvh; }
  .departure-form-dialog .el-dialog__body { max-height: 65dvh; overflow-y: auto; }
}
</style>
