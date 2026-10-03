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

const props = defineProps({
  title: { type: String, required: true },
  resource: { type: String, required: true }
})

const auth = useAuthStore()

/**
 * 只有 ADMIN 能创建导游账号、启用/停用导游：契约里 POST /admin/guides 与
 * PATCH /admin/guides/{guideId}/status 的 x-roles 都是 [ADMIN]，
 * 而 PUT /admin/guides/{guideId}（改资料）对 STAFF 开放。
 * 这里按同一口径决定按钮是否出现，避免 STAFF 点进一个必然 403 的操作。
 */
const isAdmin = computed(() => auth.hasRole('ADMIN'))

const rows = ref([])
const loading = ref(false)
/** 正在提交审核的退款单 id；用来禁用按钮，防止重复点出两次出款请求。 */
const pending = ref(null)
/**
 * 正在提交启停的导游 id 集合，用 `Set` 而不是单个标量：两个导游可以各点一次，
 * 各自独立地在途，互不阻塞。
 */
const pendingGuideStatus = ref(new Set())

/**
 * 团期新增/编辑弹窗状态。线路与导游候选项由弹窗自己分页加载
 * （契约 Size 上限 100，放在这里只取第一页会让后续记录选不到）。
 */
const departureDialogVisible = ref(false)
const editingDeparture = ref(null)

/** 景点新增/编辑弹窗状态，对应契约 /admin/attractions 的写入端点。 */
const attractionDialogVisible = ref(false)
const editingAttraction = ref(null)

/** 酒店资料新增/编辑弹窗状态，对应契约 /admin/hotels 的写入端点。 */
const hotelDialogVisible = ref(false)
const editingHotel = ref(null)

/** 导游新增/编辑弹窗状态，对应契约 /admin/guides 的写入端点。 */
const guideDialogVisible = ref(false)
const editingGuide = ref(null)

/** 景点与酒店列表共用的 keyword 筛选（契约两者的 GET 端点都声明了 keyword 参数）。 */
const keyword = ref('')

const city = ref('')

/**
 * 契约 AccountStatus：景点 / 酒店 / 导游共用同一套枚举，停用即停止使用
 * （景点从用户端列表与详情撤下；酒店不再被安排进新的每日行程，后端在写行程时以 422 拒绝；
 * 导游的账号同步被冻结，无法登录）。
 */
const ACCOUNT_STATUS_LABEL = { ACTIVE: '启用', DISABLED: '停用' }

const loaders = {
  attractions: adminApi.attractions,
  hotels: adminApi.hotels,
  guides: adminApi.guides,
  departures: adminApi.departures,
  refunds: adminApi.refunds
}

/** 契约 DepartureStatus 的全部取值，与后端枚举一一对应。 */
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

/** 终态：与后端一致，不能再改回其它状态。 */
const TERMINAL_DEPARTURE_STATUSES = ['FINISHED', 'CANCELLED']

function localToday() {
  const now = new Date()
  const pad = (value) => String(value).padStart(2, '0')
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`
}

/**
 * 某个状态是否是这条团期当前可以切到的目标，与后端的服务端规则保持一致：
 * 终态不可回退；已经出发的团期不能再开放报名（下单只校验状态是 OPEN）。
 * 当前状态本身始终可选，否则下拉会显示成一个被禁用的项。
 */
function isDepartureStatusAllowed(row, status) {
  if (status === row.status) return true
  if (TERMINAL_DEPARTURE_STATUSES.includes(row.status)) return false
  if (status === 'OPEN' && row.startDate && row.startDate < localToday()) return false
  return true
}

/**
 * 退款状态文案。`PROCESSING` 特别重要：它不是「审核中」，而是**出款已发出、结果还没确认**
 * （钱可能已经退出去），后端会把它持久化，并且在确认之前禁止拒绝。
 */
const REFUND_STATUS_LABEL = {
  APPLYING: '待审核',
  PROCESSING: '退款结果待确认',
  REFUNDED: '已退款',
  REJECTED: '已拒绝'
}

const refundLabel = (status) => REFUND_STATUS_LABEL[status] || status
const refundTagClass = (status) =>
  status === 'REFUNDED' ? 'success' : status === 'REJECTED' ? 'danger' : 'warning'

/**
 * 景点列表与酒店列表的分页状态（契约 GET /admin/attractions 与 GET /admin/hotels 的 page / size）。
 *
 * <p>不传分页参数时后端按 page=1、size=20 返回，页面上没有任何翻页入口，
 * 第 21 条之后的资料就<b>无法在界面里被管理</b>（既看不到也没法编辑或删除）。
 * 因此这一档必须显式传 page / size 并提供翻页控件。</p>
 */
const page = ref(1)
const PAGE_SIZE = 20
const total = ref(0)
const totalPages = ref(0)

/**
 * 契约里声明了 page / size 的资源：列表按页取，并且必须有翻页控件 ——
 * 不传分页参数时后端按 page=1、size=20 返回，第 21 条之后就无法在界面上管理。
 */
const PAGED_RESOURCES = ['attractions', 'hotels', 'guides']
/**
 * 其中又声明了 keyword 参数的资源。契约 GET /admin/guides 只有 page/size/status、
 * 没有 keyword，因此导游列表不做关键字筛选，避免把契约外的查询参数打给后端。
 */
const SEARCHABLE_RESOURCES = ['attractions', 'hotels']

const CITY_FILTERED_RESOURCES = ['hotels']

const pagedResource = computed(() => PAGED_RESOURCES.includes(props.resource))
const searchable = computed(() => SEARCHABLE_RESOURCES.includes(props.resource))
const cityFilterable = computed(() => CITY_FILTERED_RESOURCES.includes(props.resource))

/** 列表标题与空状态文案用的资源名。 */
const RESOURCE_LABEL = { attractions: '景点', hotels: '酒店', guides: '导游' }

/**
 * 拉取列表。
 *
 * @param {boolean} retryOnEmptyPage 当前页取空且不是第一页时是否回退一页重取（删除最后一条后会遇到）
 */
async function load(retryOnEmptyPage = true) {
  loading.value = true
  try {
    // 只有契约里有分页参数的资源才传查询参数；其它资源不传，避免拼出后端不认识的查询串。
    const searching = keyword.value.trim()
    const cityQuery = city.value.trim()
    const params = pagedResource.value
      ? {
          page: page.value,
          size: PAGE_SIZE,
          // keyword 只发给契约里声明了它的资源（景点 / 酒店）。
          ...(searchable.value && searching ? { keyword: searching } : {}),
          ...(cityFilterable.value && cityQuery ? { city: cityQuery } : {})
        }
      : undefined
    const result = await loaders[props.resource](params)
    rows.value = result?.items || []
    if (!pagedResource.value) return

    total.value = Number(result?.total ?? rows.value.length)
    totalPages.value = Number(result?.totalPages ?? (rows.value.length ? 1 : 0))
    // 删掉当前页最后一条后，这一页可能已经空了：回退一页，而不是停在一个空页上。
    if (retryOnEmptyPage && !rows.value.length && page.value > 1) {
      page.value -= 1
      await load(false)
    }
  } finally {
    loading.value = false
  }
}

/** 翻页：delta 为 -1 / +1，越界时不动。 */
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

/** 清空筛选项并回到第 1 页。 */
function clearKeyword() {
  keyword.value = ''
  city.value = ''
  page.value = 1
  load()
}

/** 新增成功后回到第 1 页：后台列表按创建时间倒序，新建的记录就在第一页。 */
function reloadFirstPage() {
  page.value = 1
  load()
}

/**
 * 景点保存成功后的刷新策略，按 POST / PUT 分开：
 *
 * <p>① 新增（POST）—— 记录按 {@code created_at DESC} 排在第一页，回第 1 页才能看到它；
 * ② 修改（PUT）—— {@code created_at} 不变，被改的那条仍在原来的页码上。
 * 旧实现两种情况都回第 1 页：在第 2 页改完一个景点后，列表跳回第 1 页，
 * 刚编辑的那条从视野里消失，看起来像是被删掉了。修改后留在当前页（连同 keyword 筛选）。</p>
 *
 * @param {{ created?: boolean }} payload 由 AttractionFormDialog 的 saved 事件给出
 */
function onAttractionSaved(payload) {
  if (payload?.created) reloadFirstPage()
  else load()
}

/** 打开景点新增/编辑弹窗；row 为空表示新增。 */
function openAttractionDialog(row) {
  editingAttraction.value = row || null
  attractionDialogVisible.value = true
}

/** 打开酒店资料新增/编辑弹窗；row 为空表示新增。 */
function openHotelDialog(row) {
  editingHotel.value = row || null
  hotelDialogVisible.value = true
}

/** 酒店保存成功后的刷新策略与景点同口径：新增回第 1 页，修改留在当前页。 */
function onHotelSaved(payload) {
  if (payload?.created) reloadFirstPage()
  else load()
}

/** 打开导游新增/编辑弹窗；row 为空表示新增。 */
function openGuideDialog(row) {
  editingGuide.value = row || null
  guideDialogVisible.value = true
}

/** 导游保存成功后的刷新策略与景点/酒店同口径：新增回第 1 页，修改留在当前页。 */
function onGuideSaved(payload) {
  if (payload?.created) reloadFirstPage()
  else load()
}

/**
 * 启用 / 停用导游，走契约 PATCH /admin/guides/{guideId}/status（仅 ADMIN，成功 200 + 更新后的导游）。
 *
 * <p>停用只是把导游标记为不可用：不删除资料，也不改动已经分配给该导游的历史团期；
 * 导游账号同步被冻结（后端把 sys_user.status 一起改掉），登录会被拒绝。</p>
 *
 * <p><b>同一导游必须串行</b>：状态是「乐观改写 + 失败回滚」，而按钮的文案又由
 * `row.status` 反推 —— 只要同一导游还有一次启停在途，第二次点击就必须被拦掉。
 * 否则快速点「停用 → 启用」会发出两个请求，两个都失败时按「后进先出」回滚：
 * 后发的把 `row.status` 写回 `DISABLED`，而库内其实仍是 `ACTIVE`
 * （第一次请求从未落库），页面就此停在一个与数据库相反的状态上，
 * 运营还会照着这个错状态继续操作。`pendingGuideStatus` 既是请求中的标记，
 * 也是按钮 `:disabled` 的依据（导航空中不会出现第二个 `changeGuideStatus` 调用）。</p>
 *
 * <p>失败时把本地状态回滚为改动前的值；错误提示由 axios 拦截器统一弹出，这里不重复提示。</p>
 */
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
    // 只回滚这一行：期间列表可能已被重新拉取（换成了新的行对象），
    // 但对旧对象的赋值不会影响界面，因此不会覆盖新数据。
    row.status = previous
  } finally {
    pendingGuideStatus.value.delete(row.id)
  }
}

/**
 * 删除酒店资料，走契约 DELETE /admin/hotels/{hotelId}（成功 204）。
 *
 * 契约只允许删除<b>未被行程引用</b>的酒店：仍被线路每日行程引用时后端返回 409，
 * 错误提示由 axios 拦截器统一弹出（message 里点明了是被线路行程挡住的），这里不重复提示。
 * 想让酒店停止使用而不影响引用它的行程时，应改用「停用」而不是删除。
 */
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
    // 失败提示已由拦截器给出；无论成功与否都重新拉取列表，避免页面停留在与库内不符的状态。
  } finally {
    await load().catch(() => {})
  }
}

/**
 * 删除景点资料，走契约 DELETE /admin/attractions/{attractionId}（成功 204）。
 *
 * 契约只允许删除<b>未被引用</b>的景点：仍被线路行程、地点指南或攻略文章引用时后端返回 409，
 * 错误提示由 axios 拦截器统一弹出（message 里点明了是被什么挡住的），这里不重复提示。
 * 想让景点从用户端撤下而不影响已引用它的线路时，应改用「停用」而不是删除。
 */
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
    // 失败提示已由拦截器给出；无论成功与否都重新拉取列表，避免页面停留在与库内不符的状态。
  } finally {
    await load().catch(() => {})
  }
}

/** 打开团期新增/编辑弹窗；候选项由弹窗自行分页加载。 */
function openDepartureDialog(row) {
  editingDeparture.value = row || null
  departureDialogVisible.value = true
}

/**
 * 修改团期运营状态，走契约 PATCH /admin/departures/{departureId}/status。
 *
 * 失败时把本地状态回滚为改动前的值，避免页面停留在一个并未落库的状态上；
 * 错误提示由 axios 拦截器统一弹出（见 frontend/src/api/request.js），这里不重复提示。
 */
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

/**
 * 提交一次退款审核动作。
 *
 * <p>两种情况都要重新拉列表，因此刷新放在 finally 里：
 * ① 成功 —— 状态已从 APPLYING 变成 REFUNDED / REJECTED；
 * ② 失败且是「结果未确认」（后端 503 `REFUND_RESULT_UNCONFIRMED`）—— 此时出款请求已经发出去，
 * 后端把退款单落成了持久的 PROCESSING，页面若还停在旧的 `APPLYING` 上，
 * 管理员就会对着一个早已不接受拒绝的单子继续点「拒绝」（后端会回 409，白点一次）。</p>
 *
 * <p>{@code PROCESSING} 的重试走的就是 APPROVE：后端对已处于 PROCESSING 的单子放行同意、
 * 拦掉拒绝，用同一个出款请求号再确认一次结果。</p>
 */
async function decision(row, action) {
  if (pending.value != null) return
  pending.value = row.id
  const comment = action === 'APPROVE' ? '审核通过，已进入原路退款流程' : '申请原因需要进一步核实'
  try {
    if (action === 'APPROVE') await adminApi.approveRefund(row.id, comment)
    else await adminApi.rejectRefund(row.id, comment)
    ElMessage.success(action === 'APPROVE' ? '退款审核已处理完毕' : '退款申请已驳回')
  } catch {
    // 失败提示由 axios 拦截器统一弹出（frontend/src/api/request.js 的 ElMessage.error）；
    // 这里不重抛，避免在点击处理器里留下未处理的 Promise rejection。
  } finally {
    pending.value = null
    await load().catch(() => {})
  }
}

watch(() => props.resource, () => {
  // 切换资源时清掉上一个资源的筛选词与页码，避免把景点的 keyword/页码带到别的列表上。
  keyword.value = ''
  city.value = ''
  page.value = 1
  load()
})
onMounted(load)
</script>

<template>
  <div class="admin-resources-page">
    <div class="admin-page-head">
      <div>
        <h2>{{ title }}</h2>
        <p>维护基础业务资源档案、可追溯资料与审核流。</p>
      </div>
      <button
        v-if="resource === 'departures'"
        class="primary-button"
        @click="openDepartureDialog(null)"
      >
        + 新增团期
      </button>
      <button
        v-else-if="resource === 'attractions'"
        class="primary-button"
        @click="openAttractionDialog(null)"
      >
        + 新增景点资料
      </button>
      <button
        v-else-if="resource === 'hotels'"
        class="primary-button"
        @click="openHotelDialog(null)"
      >
        + 新增酒店资料
      </button>
      <button
        v-else-if="resource === 'guides' && isAdmin"
        class="primary-button"
        @click="openGuideDialog(null)"
      >
        + 新增导游
      </button>
    </div>

    <!-- 景点与酒店资料的 keyword 筛选，对应契约 GET 端点的 keyword 参数（导游列表没有该参数） -->
    <form v-if="searchable" class="resource-search" @submit.prevent="search">
      <!-- 不设 maxlength：它按 UTF-16 码元截断 emoji 关键字，超长改由 search() 按码点提示 -->
      <input
        v-model="keyword"
        :placeholder="resource === 'hotels'
          ? '按酒店名称、地址或简介搜索'
          : '按景点名称、所属城市或简介搜索'"
        :aria-label="resource === 'hotels'
          ? '按酒店名称、地址或简介搜索'
          : '按景点名称、所属城市或简介搜索'"
      />

      <input
        v-if="cityFilterable"
        v-model="city"
        class="city-filter"
        placeholder="按城市精确筛选，例如：杭州"
        aria-label="按城市精确筛选"
      />
      <button type="submit" class="secondary-button" :disabled="loading">查询</button>
      <button
        v-if="keyword || city"
        type="button"
        class="text-button"
        :disabled="loading"
        @click="clearKeyword"
      >
        清空
      </button>
    </form>

    <div class="admin-panel">
      <div v-if="loading">
        <el-skeleton :rows="8" animated />
      </div>

      <table v-else-if="rows.length" class="data-table">
        <thead>
          <tr v-if="resource === 'departures'">
            <th>所属线路</th>
            <th>出发日期</th>
            <th>返程日期</th>
            <th>成人价 / 儿童价</th>
            <th>已占用 / 名额上限</th>
            <th>带团导游</th>
            <th>状态</th>
            <th style="text-align: right;">操作</th>
          </tr>
          <tr v-else-if="resource === 'refunds'">
            <th>退款申请单号</th>
            <th>关联订单号</th>
            <th>申请金额</th>
            <th>申请退款原因</th>
            <th>审核状态</th>
            <th style="text-align: right;">操作</th>
          </tr>
          <tr v-else-if="resource === 'guides'">
            <th>导游姓名</th>
            <th>登录账号</th>
            <th>联系电话</th>
            <th>个人简介</th>
            <th>状态</th>
            <th style="text-align: right;">操作</th>
          </tr>
          <tr v-else-if="resource === 'attractions'">
            <th>景点名称</th>
            <th>所属城市</th>
            <th>地址</th>
            <th>地理经纬度</th>
            <th>资料来源</th>
            <th>状态</th>
            <th style="text-align: right;">操作</th>
          </tr>
          <tr v-else-if="resource === 'hotels'">
            <th>酒店名称</th>
            <th>城市</th>
            <th>地址</th>
            <th>联系电话</th>
            <th>地理经纬度</th>
            <th>资料来源</th>
            <th>状态</th>
            <th style="text-align: right;">操作</th>
          </tr>
          <tr v-else>
            <th>名称</th>
            <th>所属城市 / 地址</th>
            <th>地理经纬度</th>
            <th>资料来源</th>
            <th>状态</th>
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
                <select
                  class="status-select"
                  :value="row.status"
                  @change="changeDepartureStatus(row, $event.target.value)"
                >
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
              <td>{{ row.reason }}</td>
              <td>
                <span class="tag" :class="refundTagClass(row.status)">
                  {{ refundLabel(row.status) }}
                </span>
              </td>
              <td style="text-align: right;">
                <template v-if="row.status === 'APPLYING'">
                  <button type="button" class="text-button text-success" :disabled="pending === row.id" @click="decision(row, 'APPROVE')">同意退款</button>
                  <span class="divider">|</span>
                  <button type="button" class="text-button text-danger" :disabled="pending === row.id" @click="decision(row, 'REJECT')">拒绝</button>
                </template>
                <!--
                  出款已发出、结果未确认。钱可能已经退出去，所以这里只给「重试确认」
                  （同一个出款请求号再查一次结果），绝不显示拒绝 —— 后端对 PROCESSING 的
                  REJECT 会判 409，前端不该给出一个必然失败的按钮。
                -->
                <template v-else-if="row.status === 'PROCESSING'">
                  <button type="button" class="text-button text-success" :disabled="pending === row.id" @click="decision(row, 'APPROVE')">
                    {{ pending === row.id ? '确认中…' : '重试确认' }}
                  </button>
                </template>
                <span v-else class="muted-text">已处理完毕</span>
              </td>
            </tr>

            <tr v-else-if="resource === 'guides'">
              <td>
                <strong>{{ row.name }}</strong>
                <div class="muted-text">导游 #{{ row.id }}</div>
              </td>
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
                    {{ pendingGuideStatus.has(row.id)
                      ? '提交中…'
                      : row.status === 'ACTIVE' ? '停用' : '启用' }}
                  </button>
                </template>
              </td>
            </tr>

            <tr v-else-if="resource === 'attractions'">
              <td>
                <strong>{{ row.name }}</strong>
                <div class="muted-text">景点 #{{ row.id }}</div>
              </td>
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
              <td>
                <strong>{{ row.name }}</strong>
                <div class="muted-text">酒店 #{{ row.id }}</div>
              </td>

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
        {{ searchable && keyword.trim()
          ? `没有匹配「${keyword.trim()}」的${RESOURCE_LABEL[resource] || ''}资料，可清空筛选后重试。`
          : cityFilterable && city.trim()
            ? `没有位于「${city.trim()}」的${RESOURCE_LABEL[resource] || ''}资料，城市筛选是精确匹配，可清空筛选后重试。`
            : resource === 'guides' ? '暂无导游数据。' : '暂无相关资料数据。' }}
      </div>

      <!-- 景点 / 酒店 / 导游列表分页：后端按 page/size 分页返回，没有这组控件时第 21 条之后无法在界面上管理 -->
      <div v-if="pagedResource" class="resource-pager">
        <button
          type="button"
          class="secondary-button"
          :disabled="loading || page <= 1"
          @click="goToPage(-1)"
        >
          上一页
        </button>
        <span class="muted-text">
          第 {{ page }} / {{ totalPages || 1 }} 页，共 {{ total }} 条
        </span>
        <button
          type="button"
          class="secondary-button"
          :disabled="loading || page >= totalPages"
          @click="goToPage(1)"
        >
          下一页
        </button>
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
  </div>
</template>

<style scoped>
.resource-search {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
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

.text-success {
  color: var(--success-text) !important;
}

.text-danger {
  color: var(--danger-red) !important;
}

.muted-text {
  color: var(--text-tertiary);
  font-size: 12px;
}
</style>
