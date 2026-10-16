<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { adminApi } from '@/api/modules'
import { useAuthStore } from '@/stores/auth'
import AttractionFormDialog from '@/components/AttractionFormDialog.vue'
import DepartureFormDialog from '@/components/DepartureFormDialog.vue'
import GuideFormDialog from '@/components/GuideFormDialog.vue'
import HotelFormDialog from '@/components/HotelFormDialog.vue'
import { codePointLength } from '@/utils/text'
import { fetchAllPages } from '@/utils/paging'

const props = defineProps({
  title: { type: String, required: true },
  resource: { type: String, required: true }
})

const auth = useAuthStore()

/** 只有 ADMIN 能创建导游账号、启用/停用导游。 */
const isAdmin = computed(() => auth.hasRole('ADMIN'))

const rows = ref([])
const loading = ref(false)
const error = ref('')
/** 正在提交审核的退款单 id；用来禁用按钮，防止重复点出两次出款请求。 */
const pending = ref(null)
/** 正在提交启停的导游 id 集合。 */
const pendingGuideStatus = ref(new Set())

// ---------- 弹窗状态 ----------
const departureDialogVisible = ref(false)
const editingDeparture = ref(null)
const attractionDialogVisible = ref(false)
const editingAttraction = ref(null)
const hotelDialogVisible = ref(false)
const editingHotel = ref(null)
const guideDialogVisible = ref(false)
const editingGuide = ref(null)

// ---------- 筛选 ----------
const keyword = ref('')
const city = ref('')
const departureStatus = ref('')
const departureRouteId = ref('')
const departureGuideId = ref('')
const departureStartFrom = ref('')
const departureStartTo = ref('')
const refundStatus = ref('')

/** 团期筛选用的线路 / 导游候选项。 */
const departureRouteOptions = ref([])
const departureGuideOptions = ref([])
const departureOptionsLoaded = ref(false)
const departureOptionsLoading = ref(false)
const departureOptionsError = ref('')

/**
 * 加载团期筛选的线路 / 导游候选项。
 *
 * <p>契约 Size 上限是 100，只取第一页会让第 101 条之后的线路 / 导游在筛选下拉里永远选不到。
 * 用 fetchAllPages 逐页取全量；失败时不标记为已加载（允许下次重试），并给出可重试的错误信息。</p>
 */
async function loadDepartureOptions() {
  if (departureOptionsLoaded.value || departureOptionsLoading.value) return
  departureOptionsLoading.value = true
  departureOptionsError.value = ''
  try {
    const [routeList, guideList] = await Promise.all([
      fetchAllPages(adminApi.routes),
      fetchAllPages(adminApi.guides)
    ])
    departureRouteOptions.value = routeList
    departureGuideOptions.value = guideList
    departureOptionsLoaded.value = true
  } catch (cause) {
    // 失败时不标记为已加载：用户可点"重试"按钮或切走再回来重新触发
    departureRouteOptions.value = []
    departureGuideOptions.value = []
    departureOptionsError.value = cause?.message || '线路 / 导游候选项加载失败，请重试'
  } finally {
    departureOptionsLoading.value = false
  }
}

/** 失败后手动重试。 */
function retryDepartureOptions() {
  departureOptionsLoaded.value = false
  loadDepartureOptions()
}

// ---------- 状态字典 ----------
const ACCOUNT_STATUS_LABEL = { ACTIVE: '启用', DISABLED: '停用' }

const loaders = {
  attractions: adminApi.attractions,
  hotels: adminApi.hotels,
  guides: adminApi.guides,
  departures: adminApi.departures,
  refunds: adminApi.refunds
}

const DEPARTURE_STATUS_LABEL = {
  DRAFT: '草稿',
  OPEN: '报名中',
  FULL: '已满员',
  CLOSED: '已截止',
  TRAVELLING: '行程中',
  FINISHED: '已完成',
  CANCELLED: '已取消'
}
const DEPARTURE_STATUSES = Object.keys(DEPARTURE_STATUS_LABEL)

const departureStatusClass = (status) =>
  status === 'OPEN' ? 'success'
    : status === 'CANCELLED' || status === 'CLOSED' ? 'danger'
      : status === 'FINISHED' ? 'success' : 'warning'

const TERMINAL_DEPARTURE_STATUSES = ['FINISHED', 'CANCELLED']

function localToday() {
  const now = new Date()
  const pad = (value) => String(value).padStart(2, '0')
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`
}

function isDepartureStatusAllowed(row, status) {
  if (status === row.status) return true
  if (TERMINAL_DEPARTURE_STATUSES.includes(row.status)) return false
  if (status === 'OPEN' && row.startDate && row.startDate < localToday()) return false
  return true
}

const REFUND_STATUS_LABEL = {
  APPLYING: '待审核',
  PROCESSING: '退款结果待确认',
  REFUNDED: '已退款',
  REJECTED: '已拒绝'
}
const REFUND_STATUSES = Object.keys(REFUND_STATUS_LABEL)

const refundLabel = (status) => REFUND_STATUS_LABEL[status] || status
const refundTagClass = (status) =>
  status === 'REFUNDED' ? 'success' : status === 'REJECTED' ? 'danger' : 'warning'

// ---------- 分页 ----------
const page = ref(1)
const PAGE_SIZE = 20
const total = ref(0)
const totalPages = ref(0)

const PAGED_RESOURCES = ['attractions', 'hotels', 'guides', 'departures', 'refunds']
const SEARCHABLE_RESOURCES = ['attractions', 'hotels']
const CITY_FILTERED_RESOURCES = ['hotels']

const pagedResource = computed(() => PAGED_RESOURCES.includes(props.resource))
const searchable = computed(() => SEARCHABLE_RESOURCES.includes(props.resource))
const cityFilterable = computed(() => CITY_FILTERED_RESOURCES.includes(props.resource))
const isDepartureList = computed(() => props.resource === 'departures')
const isRefundList = computed(() => props.resource === 'refunds')
const hasDepartureFilters = computed(
  () =>
    Boolean(departureStatus.value) ||
    Boolean(departureRouteId.value) ||
    Boolean(departureGuideId.value) ||
    Boolean(departureStartFrom.value) ||
    Boolean(departureStartTo.value)
)

const RESOURCE_LABEL = { attractions: '景点', hotels: '酒店', guides: '导游', departures: '团期', refunds: '退款单' }

async function load(retryOnEmptyPage = true) {
  loading.value = true
  error.value = ''
  try {
    let result
    if (isDepartureList.value) {
      const params = {
        page: page.value,
        size: PAGE_SIZE,
        ...(departureStatus.value ? { status: departureStatus.value } : {}),
        ...(departureRouteId.value ? { routeId: String(departureRouteId.value) } : {}),
        ...(departureGuideId.value ? { guideId: String(departureGuideId.value) } : {}),
        ...(departureStartFrom.value ? { startDateFrom: departureStartFrom.value } : {}),
        ...(departureStartTo.value ? { startDateTo: departureStartTo.value } : {})
      }
      result = await adminApi.departures(params)
    } else if (isRefundList.value) {
      const params = {
        page: page.value,
        size: PAGE_SIZE,
        ...(refundStatus.value ? { status: refundStatus.value } : {})
      }
      result = await adminApi.refunds(params)
    } else if (pagedResource.value) {
      const searching = keyword.value.trim()
      const cityQuery = city.value.trim()
      const params = {
        page: page.value,
        size: PAGE_SIZE,
        ...(searchable.value && searching ? { keyword: searching } : {}),
        ...(cityFilterable.value && cityQuery ? { city: cityQuery } : {})
      }
      result = await loaders[props.resource](params)
    } else {
      result = await loaders[props.resource]()
    }
    rows.value = result?.items || []
    if (!pagedResource.value) return

    total.value = Number(result?.total ?? rows.value.length)
    totalPages.value = Number(result?.totalPages ?? (rows.value.length ? 1 : 0))
    if (retryOnEmptyPage && !rows.value.length && page.value > 1) {
      page.value -= 1
      await load(false)
    }
  } catch (cause) {
    rows.value = []
    error.value = cause.message || '列表加载失败'
  } finally {
    loading.value = false
  }
}

function goToPage(delta) {
  const next = page.value + delta
  if (next < 1 || (totalPages.value && next > totalPages.value)) return
  page.value = next
  load()
}

function search() {
  if (codePointLength(keyword.value.trim()) > 100) {
    ElMessage.warning('搜索关键字最多 100 个字符')
    return
  }
  if (codePointLength(city.value.trim()) > 64) {
    ElMessage.warning('城市最多 64 个字符')
    return
  }
  page.value = 1
  load()
}

function clearKeyword() {
  keyword.value = ''
  city.value = ''
  page.value = 1
  load()
}

function searchDepartures() {
  if (departureStartFrom.value && departureStartTo.value
      && departureStartFrom.value > departureStartTo.value) {
    ElMessage.warning('"出发日期从" 不能晚于 "到"')
    return
  }
  page.value = 1
  load()
}

function clearDepartureFilters() {
  departureStatus.value = ''
  departureRouteId.value = ''
  departureGuideId.value = ''
  departureStartFrom.value = ''
  departureStartTo.value = ''
  page.value = 1
  load()
}

function searchRefunds() {
  page.value = 1
  load()
}

function clearRefundFilters() {
  refundStatus.value = ''
  page.value = 1
  load()
}

function reloadFirstPage() {
  page.value = 1
  load()
}

// ---------- 景点 / 酒店 / 导游 弹窗 ----------
function onAttractionSaved(payload) {
  if (payload?.created) reloadFirstPage()
  else load()
}
function openAttractionDialog(row) {
  editingAttraction.value = row || null
  attractionDialogVisible.value = true
}

function openHotelDialog(row) {
  editingHotel.value = row || null
  hotelDialogVisible.value = true
}
function onHotelSaved(payload) {
  if (payload?.created) reloadFirstPage()
  else load()
}

function openGuideDialog(row) {
  editingGuide.value = row || null
  guideDialogVisible.value = true
}
function onGuideSaved(payload) {
  if (payload?.created) reloadFirstPage()
  else load()
}

async function changeGuideStatus(row, next) {
  if (!next || next === row.status) return
  if (pendingGuideStatus.value.has(row.id)) return
  const previous = row.status
  pendingGuideStatus.value.add(row.id)
  row.status = next
  try {
    const updated = await adminApi.updateGuideStatus(row.id, next)
    if (updated) Object.assign(row, updated)
    ElMessage.success(next === 'ACTIVE' ? '导游已启用' : '导游已停用')
  } catch {
    row.status = previous
  } finally {
    pendingGuideStatus.value.delete(row.id)
  }
}

async function removeHotel(row) {
  try {
    await ElMessageBox.confirm(
      `确认删除酒店「${row.name}」吗？已被线路行程引用的酒店不能删除，如需停止使用请改为「停用」。`,
      '删除酒店资料',
      { type: 'warning', confirmButtonText: '确认删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await adminApi.deleteHotel(row.id)
    ElMessage.success('酒店资料已删除')
  } catch {
    // 失败提示已由拦截器给出
  } finally {
    await load().catch(() => {})
  }
}

async function removeAttraction(row) {
  try {
    await ElMessageBox.confirm(
      `确认删除景点「${row.name}」吗？已被线路行程、地点指南或攻略文章引用的景点不能删除，如需下架请改为「停用」。`,
      '删除景点资料',
      { type: 'warning', confirmButtonText: '确认删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await adminApi.deleteAttraction(row.id)
    ElMessage.success('景点资料已删除')
  } catch {
    // 失败提示已由拦截器给出
  } finally {
    await load().catch(() => {})
  }
}

function openDepartureDialog(row) {
  editingDeparture.value = row || null
  departureDialogVisible.value = true
}

async function changeDepartureStatus(row, next) {
  if (!next || next === row.status) return
  const previous = row.status
  row.status = next
  try {
    const updated = await adminApi.updateDepartureStatus(row.id, next)
    if (updated) Object.assign(row, updated)
    ElMessage.success('团期状态已更新')
  } catch {
    row.status = previous
  }
}

// ---------- 退款审核 ----------

/** 退款详情弹窗。 */
const refundDetailVisible = ref(false)
const refundDetailLoading = ref(false)
const refundDetailError = ref('')
const refundDetail = ref(null)

/**
 * 退款详情请求序号：只允许「当前打开的那次」写回弹窗。
 * 打开退款 A、关闭后打开 B，A 的响应晚到时会被丢弃，弹窗不会跳回 A。
 */
let refundDetailSeq = 0
/** 当前退款详情弹窗正在查看的退款单 id；关闭弹窗时置空。 */
const activeRefundDetailId = ref(null)

async function openRefundDetail(row) {
  const seq = ++refundDetailSeq
  const refundId = row.id
  refundDetailVisible.value = true
  activeRefundDetailId.value = refundId
  refundDetailLoading.value = true
  refundDetailError.value = ''
  refundDetail.value = null
  try {
    const data = await adminApi.refund(refundId)
    if (seq !== refundDetailSeq) return
    refundDetail.value = data
  } catch (cause) {
    if (seq !== refundDetailSeq) return
    refundDetailError.value = cause.message || '退款详情加载失败'
  } finally {
    if (seq === refundDetailSeq) refundDetailLoading.value = false
  }
}

/** 关闭退款详情弹窗时让所有在途请求失效。 */
function onRefundDetailClose() {
  refundDetailSeq += 1
  activeRefundDetailId.value = null
  refundDetail.value = null
  refundDetailLoading.value = false
  refundDetailError.value = ''
}

async function promptComment(action) {
  const title = action === 'APPROVE' ? '同意退款' : '拒绝退款'
  const placeholder = action === 'APPROVE'
    ? '填写审核通过的原因（1-500 字）'
    : '填写拒绝原因（1-500 字）'
  const { value } = await ElMessageBox.prompt(
    '审核意见会一并保存并展示在退款记录里，不会包含游客隐私信息。',
    title,
    {
      confirmButtonText: action === 'APPROVE' ? '确认同意' : '确认拒绝',
      cancelButtonText: '取消',
      inputPlaceholder: placeholder,
      inputValidator: (input) => {
        const text = String(input ?? '').trim()
        if (!text) return '请填写审核意见'
        if (codePointLength(text) > 500) return '审核意见最多 500 个字符'
        return true
      },
      inputType: 'textarea',
      type: action === 'REJECT' ? 'warning' : 'info'
    }
  )
  return String(value ?? '').trim()
}

async function decision(row, action) {
  if (pending.value != null) return

  let comment
  try {
    comment = await promptComment(action)
  } catch {
    return
  }

  pending.value = row.id
  try {
    if (action === 'APPROVE') await adminApi.approveRefund(row.id, comment)
    else await adminApi.rejectRefund(row.id, comment)
    ElMessage.success(action === 'APPROVE' ? '退款审核已处理完毕' : '退款申请已驳回')
  } catch {
    // 失败提示由 axios 拦截器统一弹出
  } finally {
    pending.value = null
    await load().catch(() => {})
  }
}

watch(() => props.resource, () => {
  keyword.value = ''
  city.value = ''
  departureStatus.value = ''
  departureRouteId.value = ''
  departureGuideId.value = ''
  departureStartFrom.value = ''
  departureStartTo.value = ''
  refundStatus.value = ''
  page.value = 1
  load()
  if (props.resource === 'departures') loadDepartureOptions()
})
onMounted(() => {
  load()
  if (props.resource === 'departures') loadDepartureOptions()
})
</script>

<template>
  <div class="admin-resources-page">
    <div class="admin-page-head">
      <div>
        <h2>{{ title }}</h2>
        <p>维护基础业务资源档案、可追溯资料与审核流。</p>
      </div>
      <button v-if="resource === 'departures'" class="primary-button" @click="openDepartureDialog(null)">
        + 新增团期
      </button>
      <button v-else-if="resource === 'attractions'" class="primary-button" @click="openAttractionDialog(null)">
        + 新增景点资料
      </button>
      <button v-else-if="resource === 'hotels'" class="primary-button" @click="openHotelDialog(null)">
        + 新增酒店资料
      </button>
      <button v-else-if="resource === 'guides' && isAdmin" class="primary-button" @click="openGuideDialog(null)">
        + 新增导游
      </button>
    </div>

    <!-- 团期筛选 -->
    <form v-if="isDepartureList" class="resource-search departure-search" @submit.prevent="searchDepartures">
      <select v-model="departureStatus" class="filter-select">
        <option value="">全部状态</option>
        <option v-for="status in DEPARTURE_STATUSES" :key="status" :value="status">
          {{ DEPARTURE_STATUS_LABEL[status] }}
        </option>
      </select>
      <select v-model="departureRouteId" class="filter-select">
        <option value="">全部线路</option>
        <option v-for="route in departureRouteOptions" :key="route.id" :value="String(route.id)">
          {{ route.name }}
        </option>
      </select>
      <select v-model="departureGuideId" class="filter-select">
        <option value="">全部导游</option>
        <option v-for="guide in departureGuideOptions" :key="guide.id" :value="String(guide.id)">
          {{ guide.name }}
        </option>
      </select>
      <label class="date-label">
        出发日期
        <input v-model="departureStartFrom" type="date" class="date-input" />
        <span class="date-sep">~</span>
        <input v-model="departureStartTo" type="date" class="date-input" />
      </label>
      <button type="submit" class="secondary-button" :disabled="loading">查询</button>
      <button v-if="hasDepartureFilters" type="button" class="text-button" :disabled="loading" @click="clearDepartureFilters">清空</button>
    </form>

    <!-- 团期候选项加载失败提示（可重试） -->
    <p v-if="isDepartureList && departureOptionsError" class="options-error" role="alert">
      {{ departureOptionsError }}
      <button type="button" class="text-button" :disabled="departureOptionsLoading" @click="retryDepartureOptions">
        {{ departureOptionsLoading ? '加载中…' : '重试' }}
      </button>
    </p>

    <!-- 退款筛选 -->
    <form v-else-if="isRefundList" class="resource-search" @submit.prevent="searchRefunds">
      <select v-model="refundStatus" class="filter-select">
        <option value="">全部状态</option>
        <option v-for="status in REFUND_STATUSES" :key="status" :value="status">
          {{ REFUND_STATUS_LABEL[status] }}
        </option>
      </select>
      <button type="submit" class="secondary-button" :disabled="loading">查询</button>
      <button v-if="refundStatus" type="button" class="text-button" :disabled="loading" @click="clearRefundFilters">清空</button>
    </form>

    <!-- 景点 / 酒店 的关键字 + 城市筛选 -->
    <form v-else-if="searchable" class="resource-search" @submit.prevent="search">
      <input
        v-model="keyword"
        :placeholder="resource === 'hotels'
          ? '按酒店名称、地址或简介搜索'
          : '按景点名称、所属城市或简介搜索'"
      />
      <input v-if="cityFilterable" v-model="city" class="city-filter" placeholder="按城市精确筛选，例如：杭州" />
      <button type="submit" class="secondary-button" :disabled="loading">查询</button>
      <button v-if="keyword || city" type="button" class="text-button" :disabled="loading" @click="clearKeyword">清空</button>
    </form>

    <div class="admin-panel">
      <div v-if="loading"><el-skeleton :rows="8" animated /></div>

      <table v-else-if="rows.length" class="data-table">
        <thead>
          <tr v-if="resource === 'departures'">
            <th>所属线路</th><th>出发日期</th><th>返程日期</th>
            <th>成人价 / 儿童价</th><th>已占用 / 名额上限</th><th>带团导游</th><th>状态</th>
            <th style="text-align: right;">操作</th>
          </tr>
          <tr v-else-if="resource === 'refunds'">
            <th>退款申请单号</th>
            <th>关联订单号</th>
            <th>申请金额</th>
            <th>申请退款原因</th>
            <th>审核状态</th>
            <th>申请时间</th>
            <th style="text-align: right;">操作</th>
          </tr>
          <tr v-else-if="resource === 'guides'">
            <th>导游姓名</th><th>登录账号</th><th>联系电话</th><th>个人简介</th><th>状态</th>
            <th style="text-align: right;">操作</th>
          </tr>
          <tr v-else-if="resource === 'attractions'">
            <th>景点名称</th><th>所属城市</th><th>地址</th><th>地理经纬度</th><th>资料来源</th><th>状态</th>
            <th style="text-align: right;">操作</th>
          </tr>
          <tr v-else-if="resource === 'hotels'">
            <th>酒店名称</th><th>城市</th><th>地址</th><th>联系电话</th><th>地理经纬度</th><th>资料来源</th><th>状态</th>
            <th style="text-align: right;">操作</th>
          </tr>
          <tr v-else>
            <th>名称</th><th>所属城市 / 地址</th><th>地理经纬度</th><th>资料来源</th><th>状态</th>
          </tr>
        </thead>
        <tbody>
          <template v-for="row in rows" :key="row.id">
            <tr v-if="resource === 'departures'">
              <td>
                <strong>{{ row.routeName || `线路 #${row.routeId}` }}</strong>
                <div class="muted-text">团期 #{{ row.id }}</div>
              </td>
              <td>{{ row.startDate }}</td>
              <td>{{ row.endDate }}</td>
              <td class="amount">
                ¥{{ row.adultPrice }}
                <div class="muted-text">儿童 ¥{{ row.childPrice }}</div>
              </td>
              <td>
                {{ (row.reservedPeople || 0) + (row.confirmedPeople || 0) }} / {{ row.maxPeople }} 人
                <div class="muted-text">余位 {{ row.availableSeats }} 人</div>
              </td>
              <td>{{ row.guideName || '未分配' }}</td>
              <td>
                <span class="tag" :class="departureStatusClass(row.status)">
                  {{ DEPARTURE_STATUS_LABEL[row.status] || row.status }}
                </span>
              </td>
              <td style="text-align: right;">
                <select class="status-select" :value="row.status" @change="changeDepartureStatus(row, $event.target.value)">
                  <option
                    v-for="status in DEPARTURE_STATUSES"
                    :key="status"
                    :value="status"
                    :disabled="!isDepartureStatusAllowed(row, status)"
                  >
                    {{ DEPARTURE_STATUS_LABEL[status] }}{{ isDepartureStatusAllowed(row, status) ? '' : '（不可选）' }}
                  </option>
                </select>
                <span class="divider">|</span>
                <button type="button" class="text-button" @click="openDepartureDialog(row)">编辑</button>
              </td>
            </tr>

            <tr v-else-if="resource === 'refunds'">
              <td><strong>#{{ row.id }}</strong></td>
              <td>#{{ row.orderNo }}</td>
              <td class="amount">¥{{ row.amount }}</td>
              <td class="reason-cell">{{ row.reason }}</td>
              <td>
                <span class="tag" :class="refundTagClass(row.status)">{{ refundLabel(row.status) }}</span>
              </td>
              <td>{{ row.createdAt }}</td>
              <td style="text-align: right;" class="row-actions">
                <button type="button" class="text-button" @click="openRefundDetail(row)">详情</button>
                <template v-if="row.status === 'APPLYING'">
                  <span class="divider">|</span>
                  <button type="button" class="text-button text-success" :disabled="pending === row.id" @click="decision(row, 'APPROVE')">同意</button>
                  <span class="divider">|</span>
                  <button type="button" class="text-button text-danger" :disabled="pending === row.id" @click="decision(row, 'REJECT')">拒绝</button>
                </template>
                <template v-else-if="row.status === 'PROCESSING'">
                  <span class="divider">|</span>
                  <button type="button" class="text-button text-success" :disabled="pending === row.id" @click="decision(row, 'APPROVE')">
                    {{ pending === row.id ? '确认中…' : '重试确认' }}
                  </button>
                </template>
              </td>
            </tr>

            <tr v-else-if="resource === 'guides'">
              <td><strong>{{ row.name }}</strong><div class="muted-text">导游 #{{ row.id }}</div></td>
              <td>{{ row.username || '—' }}</td>
              <td>{{ row.phone || '—' }}</td>
              <td>{{ row.intro || '—' }}</td>
              <td>
                <span class="tag" :class="row.status === 'ACTIVE' ? 'success' : 'danger'">
                  {{ ACCOUNT_STATUS_LABEL[row.status] || row.status }}
                </span>
              </td>
              <td style="text-align: right;">
                <button type="button" class="text-button" @click="openGuideDialog(row)">编辑</button>
                <template v-if="isAdmin">
                  <span class="divider">|</span>
                  <button
                    type="button"
                    class="text-button"
                    :class="row.status === 'ACTIVE' ? 'text-danger' : 'text-success'"
                    :disabled="pendingGuideStatus.has(row.id)"
                    @click="changeGuideStatus(row, row.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE')"
                  >
                    {{ pendingGuideStatus.has(row.id) ? '提交中…' : row.status === 'ACTIVE' ? '停用' : '启用' }}
                  </button>
                </template>
              </td>
            </tr>

            <tr v-else-if="resource === 'attractions'">
              <td><strong>{{ row.name }}</strong><div class="muted-text">景点 #{{ row.id }}</div></td>
              <td>{{ row.city || '—' }}</td>
              <td>{{ row.address || '—' }}</td>
              <td>
                {{ row.longitude ?? '—' }}, {{ row.latitude ?? '—' }}
                <div v-if="row.longitude === null || row.latitude === null" class="muted-text">
                  未填写坐标，不会出现在用户端地图上
                </div>
              </td>
              <td>{{ row.dataSource || '—' }}</td>
              <td>
                <span class="tag" :class="row.status === 'ACTIVE' ? 'success' : 'danger'">
                  {{ ACCOUNT_STATUS_LABEL[row.status] || row.status }}
                </span>
              </td>
              <td style="text-align: right;">
                <button type="button" class="text-button" @click="openAttractionDialog(row)">编辑</button>
                <span class="divider">|</span>
                <button type="button" class="text-button text-danger" @click="removeAttraction(row)">删除</button>
              </td>
            </tr>

            <tr v-else-if="resource === 'hotels'">
              <td><strong>{{ row.name }}</strong><div class="muted-text">酒店 #{{ row.id }}</div></td>
              <td>{{ row.city || '—' }}</td>
              <td>{{ row.address || '—' }}</td>
              <td>{{ row.contactPhone || '—' }}</td>
              <td>
                {{ row.longitude ?? '—' }}, {{ row.latitude ?? '—' }}
                <div v-if="row.longitude === null || row.latitude === null" class="muted-text">
                  未填写坐标（仅作资料登记，酒店不参与用户端地图标注）
                </div>
              </td>
              <td>{{ row.dataSource || '—' }}</td>
              <td>
                <span class="tag" :class="row.status === 'ACTIVE' ? 'success' : 'danger'">
                  {{ ACCOUNT_STATUS_LABEL[row.status] || row.status }}
                </span>
              </td>
              <td style="text-align: right;">
                <button type="button" class="text-button" @click="openHotelDialog(row)">编辑</button>
                <span class="divider">|</span>
                <button type="button" class="text-button text-danger" @click="removeHotel(row)">删除</button>
              </td>
            </tr>

            <tr v-else>
              <td><strong>{{ row.name }}</strong></td>
              <td>{{ row.city || row.address || '—' }}</td>
              <td>{{ row.longitude || '—' }}, {{ row.latitude || '—' }}</td>
              <td>{{ row.dataSource || '—' }}</td>
              <td><span class="tag success">{{ row.status }}</span></td>
            </tr>
          </template>
        </tbody>
      </table>

      <div v-else class="empty-box">
        {{ isRefundList && refundStatus
          ? `没有状态为「${REFUND_STATUS_LABEL[refundStatus] || refundStatus}」的退款申请，可清空筛选后重试。`
          : isDepartureList && hasDepartureFilters
            ? '没有符合筛选条件的团期，可清空筛选后重试。'
            : searchable && keyword.trim()
              ? `没有匹配「${keyword.trim()}」的${RESOURCE_LABEL[resource] || ''}资料，可清空筛选后重试。`
              : cityFilterable && city.trim()
                ? `没有位于「${city.trim()}」的${RESOURCE_LABEL[resource] || ''}资料，城市筛选是精确匹配，可清空筛选后重试。`
                : resource === 'guides' ? '暂无导游数据。'
                  : resource === 'departures' ? '暂无团期数据。'
                    : resource === 'refunds' ? '暂无退款申请。'
                      : '暂无相关资料数据。' }}
      </div>

      <div v-if="pagedResource" class="resource-pager">
        <button type="button" class="secondary-button" :disabled="loading || page <= 1" @click="goToPage(-1)">上一页</button>
        <span class="muted-text">第 {{ page }} / {{ totalPages || 1 }} 页，共 {{ total }} 条</span>
        <button type="button" class="secondary-button" :disabled="loading || page >= totalPages" @click="goToPage(1)">下一页</button>
      </div>
    </div>

    <AttractionFormDialog
      v-if="resource === 'attractions'"
      v-model="attractionDialogVisible"
      :attraction="editingAttraction"
      @saved="onAttractionSaved"
    />
    <HotelFormDialog
      v-if="resource === 'hotels'"
      v-model="hotelDialogVisible"
      :hotel="editingHotel"
      @saved="onHotelSaved"
    />
    <GuideFormDialog
      v-if="resource === 'guides'"
      v-model="guideDialogVisible"
      :guide="editingGuide"
      @saved="onGuideSaved"
    />
    <DepartureFormDialog
      v-if="resource === 'departures'"
      v-model="departureDialogVisible"
      :departure="editingDeparture"
      @saved="load"
    />

    <!-- 退款详情弹窗 -->
    <el-dialog
      v-model="refundDetailVisible"
      title="退款详情"
      width="min(640px, calc(100vw - 32px))"
      @close="onRefundDetailClose"
    >
      <div v-if="refundDetailLoading"><el-skeleton :rows="5" animated /></div>

      <div v-else-if="refundDetailError" class="request-state" role="alert">
        <strong>暂时无法加载</strong>
        <p>{{ refundDetailError }}</p>
      </div>

      <div v-else-if="refundDetail" class="refund-detail">
        <dl class="detail-list">
          <div><dt>退款单号</dt><dd>#{{ refundDetail.id }}</dd></div>
          <div><dt>关联订单号</dt><dd>#{{ refundDetail.orderNo }}</dd></div>
          <div><dt>用户 ID</dt><dd>#{{ refundDetail.userId }}</dd></div>
          <div><dt>申请金额</dt><dd class="amount">¥{{ refundDetail.amount }}</dd></div>
          <div><dt>退款原因</dt><dd>{{ refundDetail.reason }}</dd></div>
          <div>
            <dt>申请前订单状态</dt>
            <dd>{{ refundDetail.originalOrderStatus }}</dd>
          </div>
          <div>
            <dt>退款状态</dt>
            <dd>
              <span class="tag" :class="refundTagClass(refundDetail.status)">
                {{ refundLabel(refundDetail.status) }}
              </span>
            </dd>
          </div>
          <div><dt>申请时间</dt><dd>{{ refundDetail.createdAt }}</dd></div>
        </dl>

        <h4 class="audit-title">审核记录</h4>
        <dl v-if="refundDetail.reviewedAt || refundDetail.reviewComment" class="detail-list">
          <div><dt>审核人 ID</dt><dd>{{ refundDetail.reviewedBy ? `#${refundDetail.reviewedBy}` : '—' }}</dd></div>
          <div><dt>审核时间</dt><dd>{{ refundDetail.reviewedAt || '—' }}</dd></div>
          <div><dt>审核意见</dt><dd>{{ refundDetail.reviewComment || '—' }}</dd></div>
        </dl>
        <p v-else class="empty-inline">尚未审核。</p>

        <!-- PROCESSING 时的安全提示 -->
        <div v-if="refundDetail.status === 'PROCESSING'" class="processing-warning">
          <strong>退款结果待确认</strong>
          <p>出款请求已发出、结果尚未确认，钱可能已经退给游客。</p>
          <p>请勿点「拒绝」——只能通过「重试确认」沿用同一出款请求号再次确认结果。</p>
        </div>
      </div>
    </el-dialog>
  </div>
</template>

<style scoped>
.resource-search {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}

.resource-search input {
  flex: 1 1 260px;
  max-width: 420px;
  padding: 7px 10px;
  border: 1px solid var(--border-strong);
  border-radius: 8px;
  background: white;
  font-size: 13px;
  color: var(--text-primary);
}

.resource-search input.city-filter {
  flex: 0 1 200px;
  max-width: 200px;
}

.departure-search .date-label {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--text-secondary);
}

.departure-search .date-input {
  flex: 0 0 auto;
  width: 140px;
  padding: 6px 8px;
  border: 1px solid var(--border-strong);
  border-radius: 8px;
  background: white;
  font-size: 13px;
  color: var(--text-primary);
}

.departure-search .date-sep {
  color: var(--text-tertiary);
}

.options-error {
  margin: 0 0 12px;
  padding: 8px 12px;
  border-left: 3px solid var(--danger-red, #dc2626);
  border-radius: 6px;
  background: #fef2f2;
  color: var(--danger-red, #dc2626);
  font-size: 12px;
}

.resource-pager {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 12px;
}

.amount {
  color: var(--price-orange);
  font-weight: 800;
}

.status-select {
  padding: 3px 6px;
  border: 1px solid var(--border-strong);
  border-radius: 6px;
  background: white;
  font-size: 12px;
  color: var(--text-primary);
}

.divider {
  color: var(--border-strong);
  margin: 0 6px;
  font-size: 11px;
}

.row-actions { white-space: nowrap; }

.reason-cell {
  max-width: 280px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.text-success { color: var(--success-text) !important; }
.text-danger { color: var(--danger-red) !important; }
.muted-text { color: var(--text-tertiary); font-size: 12px; }
.empty-inline { font-size: 12px; color: var(--text-tertiary); }

.refund-detail .detail-list {
  display: grid;
  gap: 10px;
  margin: 0 0 12px;
}

.refund-detail .detail-list > div {
  display: grid;
  grid-template-columns: 120px 1fr;
  gap: 8px;
}

.refund-detail dt {
  font-size: 12px;
  color: var(--text-tertiary);
}

.refund-detail dd {
  margin: 0;
  font-size: 13px;
  color: var(--text-primary);
  word-break: break-word;
}

.audit-title {
  margin: 16px 0 10px;
  font-size: 14px;
}

.processing-warning {
  margin-top: 14px;
  padding: 10px 12px;
  border-left: 3px solid var(--status-orange, #f59e0b);
  border-radius: 6px;
  background: var(--bg-warning-subtle, #fffbeb);
  color: var(--status-orange-strong, #b45309);
  font-size: 12px;
}

.processing-warning strong {
  display: block;
  margin-bottom: 4px;
  font-size: 13px;
}

.processing-warning p {
  margin: 4px 0 0;
}
</style>