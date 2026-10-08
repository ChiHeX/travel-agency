<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { adminApi } from '@/api/modules'
import RequestState from '@/components/RequestState.vue'
import RouteFormDialog from '@/components/RouteFormDialog.vue'
import PanelIconButton from '@/components/PanelIconButton.vue'
import { fetchAllPages } from '@/utils/paging'
import { codePointLength } from '@/utils/text'
import { ACCOMMODATION_TYPES, accommodationSummary, accommodationTypeOf } from '@/utils/hotel'

/**
 * 线路管理详情页：线路资料、团期概览、每日行程与行程项目管理。
 *
 * 数据来源：GET /admin/routes/{routeId}（route/departures/itinerary/reviews），
 * 所有写操作都走契约中的 Admin Routes 接口，不在前端伪造或缓存业务数据。
 */
const vRoute = useRoute()
const routeId = vRoute.params.id

const detail = ref(null)
const loading = ref(false)
const error = ref('')
const statusUpdating = ref(false)
const editDialogVisible = ref(false)

const hotels = ref([])
const attractions = ref([])
const optionsLoaded = ref(false)

const dayDialogVisible = ref(false)
const daySaving = ref(false)
const dayError = ref('')
const editingDayId = ref(null)

const itemDialogVisible = ref(false)
const itemSaving = ref(false)
const itemError = ref('')
const itemDayId = ref(null)
const editingItemId = ref(null)

const dayForm = reactive({
  dayNumber: 1,
  title: '',
  description: '',
  transportation: '',
  meals: '',
  hotelId: '',
  accommodationType: 'PENDING',
  accommodationStandard: '',
  roomType: '',
  breakfastIncluded: '',
  accommodationNote: ''
})

const itemForm = reactive({
  sortNo: 1,
  itemType: 'ATTRACTION',
  name: '',
  description: '',
  attractionId: '',
  longitude: '',
  latitude: ''
})

const routeInfo = computed(() => detail.value?.route || null)
const departures = computed(() => detail.value?.departures || [])
const days = computed(() => detail.value?.itinerary || [])
const reviews = computed(() => detail.value?.reviews || [])
const isPublished = computed(() => routeInfo.value?.status === 'PUBLISHED')

const statusNames = { DRAFT: '草稿待上架', PUBLISHED: '已上架销售', OFFLINE: '已下架' }
const statusClasses = { DRAFT: 'warning', PUBLISHED: 'success', OFFLINE: 'danger' }
const departureStatusNames = {
  DRAFT: '草稿', OPEN: '报名中', FULL: '已满员', CLOSED: '已截止',
  TRAVELLING: '行程中', FINISHED: '已完成', CANCELLED: '已取消'
}
const itemTypeNames = {
  ATTRACTION: '景点', TRANSPORT: '交通', MEAL: '餐食', ACTIVITY: '活动', OTHER: '其他'
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    detail.value = await adminApi.route(routeId)
  } catch (cause) {
    detail.value = null
    error.value = cause.message || '线路详情加载失败'
  } finally {
    loading.value = false
  }
}

/**
 * 行程表单需要的酒店 / 景点候选项，全部来自后端真实数据。
 *
 * 逐页取全量（契约 size 上限 100）：只取第一页时，第 101 条之后的酒店 / 景点在界面上永远
 * 选不到，使用者只会以为这条数据不存在。
 */
async function loadOptions() {
  if (optionsLoaded.value) return
  optionsLoaded.value = true
  try {
    const [hotelList, attractionList] = await Promise.all([
      fetchAllPages(adminApi.hotels),
      fetchAllPages(adminApi.attractions)
    ])
    hotels.value = hotelList
    attractions.value = attractionList
  } catch {
    // 候选项加载失败不影响手写行程文本，用户仍可正常保存行程。
    hotels.value = []
    attractions.value = []
  }
}

/**
 * 停用的酒店不能再被安排进新行程（后端会以 422 拒绝），下拉里直接禁掉并标注。
 *
 * 例外是"这一天原本就指向它"：那种情况下必须保持可选。若一并禁掉，
 * 编辑这条行程时下拉会显示成空的，连带改个餐食说明都会保存失败（后端会拦新指向停用酒店）。
 */
function isHotelDisabledOption(hotel) {
  return hotel.status !== 'ACTIVE' && String(hotel.id) !== String(dayForm.hotelId)
}

async function toggleStatus() {
  if (!routeInfo.value || statusUpdating.value) return
  const next = isPublished.value ? 'OFFLINE' : 'PUBLISHED'
  statusUpdating.value = true
  try {
    await adminApi.updateRouteStatus(routeId, next)
    ElMessage.success(next === 'PUBLISHED' ? '线路已上架' : '线路已下架')
    await load()
  } catch (cause) {
    if (cause.status === 409) ElMessage.warning(cause.message || '线路当前状态不允许该操作')
    else if (cause.status !== 401) ElMessage.error(cause.message || '状态更新失败')
  } finally {
    statusUpdating.value = false
  }
}

// ---------------- 每日行程 ----------------

function openDayDialog(day) {
  loadOptions()
  dayError.value = ''
  editingDayId.value = day?.id || null
  Object.assign(dayForm, {
    dayNumber: day?.dayNumber || nextDayNumber(),
    title: day?.title || '',
    description: day?.description || '',
    transportation: day?.transportation || '',
    meals: day?.meals || '',
    hotelId: day?.hotelId || '',
    accommodationType: day ? accommodationTypeOf(day) : 'PENDING',
    accommodationStandard: day?.accommodationStandard || '',
    roomType: day?.roomType || '',
    breakfastIncluded: breakfastSelectValue(day?.breakfastIncluded),
    accommodationNote: day?.accommodationNote || ''
  })
  dayDialogVisible.value = true
}

function breakfastSelectValue(value) {
  if (value === true) return 'true'
  if (value === false) return 'false'
  return ''
}

function breakfastPayloadValue() {
  if (dayForm.breakfastIncluded === 'true') return true
  if (dayForm.breakfastIncluded === 'false') return false
  return null
}

watch(() => dayForm.hotelId, (value) => {
  if (value) dayForm.accommodationType = 'HOTEL'
})

/** watch 在 v-model 更新后清理酒店关联，避免读取旧类型。 */
watch(() => dayForm.accommodationType, (value) => {
  if (value !== 'HOTEL') dayForm.hotelId = ''
})

function nextDayNumber() {
  return days.value.reduce((max, day) => Math.max(max, day.dayNumber || 0), 0) + 1
}

function optional(value) {
  const trimmed = (value || '').toString().trim()
  return trimmed === '' ? null : trimmed
}

async function saveDay() {
  if (daySaving.value) return
  dayError.value = ''
  const dayNumber = Number(dayForm.dayNumber)
  const title = dayForm.title.trim()
  if (!Number.isInteger(dayNumber) || dayNumber < 1) return ElMessage.warning('行程天数序号应为大于 0 的整数')
  if (!title) return ElMessage.warning('请填写当日行程标题')
  // 契约 ItineraryDayRequest 的长度上限（title 200、description 10000、transportation/meals 255）
  // 数的是字符（码点），与后端 @CodePointLength 同口径；JS 的 String#length 数的是 UTF-16 码元
  // （一个 emoji 记 2），会把契约允许的文案判成超长。这里校验即将提交的值（已 trim），
  // 与 optional() 送出的内容一致，避免尾随空格造成误判。
  if (codePointLength(title) > 200) return ElMessage.warning('行程标题最多 200 个字符')
  const description = optional(dayForm.description)
  const transportation = optional(dayForm.transportation)
  const meals = optional(dayForm.meals)
  if (codePointLength(description) > 10000) return ElMessage.warning('行程说明最多 10000 个字符')
  if (codePointLength(transportation) > 255) return ElMessage.warning('交通说明最多 255 个字符')
  if (codePointLength(meals) > 255) return ElMessage.warning('餐食说明最多 255 个字符')

  const accommodationType = dayForm.accommodationType
  if (!ACCOMMODATION_TYPES.includes(accommodationType)) return ElMessage.warning('请选择住宿安排类型')
  let hotelId = optional(dayForm.hotelId)
  const accommodationStandard = optional(dayForm.accommodationStandard)
  if (accommodationType === 'HOTEL' && !hotelId) {
    return ElMessage.warning('住宿安排为「指定酒店」时，请选择具体酒店')
  }
  if (accommodationType === 'STANDARD' && !accommodationStandard) {
    return ElMessage.warning('住宿安排为「只确定住宿标准」时，请填写住宿标准')
  }
  if (accommodationType !== 'HOTEL') hotelId = null
  const hasAccommodation = accommodationType !== 'NONE'
  const standard = hasAccommodation ? accommodationStandard : null
  const roomType = hasAccommodation ? optional(dayForm.roomType) : null
  const breakfastIncluded = hasAccommodation ? breakfastPayloadValue() : null
  const accommodationNote = optional(dayForm.accommodationNote)
  if (codePointLength(standard) > 500) return ElMessage.warning('住宿标准最多 500 个字符')
  if (codePointLength(roomType) > 100) return ElMessage.warning('房型最多 100 个字符')
  if (codePointLength(accommodationNote) > 1000) return ElMessage.warning('住宿说明最多 1000 个字符')

  const payload = {
    dayNumber,
    title,
    description,
    transportation,
    meals,
    hotelId,
    accommodationType,
    accommodationStandard: standard,
    roomType,
    breakfastIncluded,
    accommodationNote
  }

  daySaving.value = true
  try {
    if (editingDayId.value) await adminApi.updateItineraryDay(editingDayId.value, payload)
    else await adminApi.createItineraryDay(routeId, payload)
    dayDialogVisible.value = false
    ElMessage.success(editingDayId.value ? '行程已更新' : '行程已新增')
    await load()
  } catch (cause) {
    dayError.value = cause.message || '行程保存失败'
  } finally {
    daySaving.value = false
  }
}

async function removeDay(day) {
  try {
    await ElMessageBox.confirm(
      `确认删除第 ${day.dayNumber} 天「${day.title}」及其 ${day.items?.length || 0} 个行程项目吗？`,
      '删除行程',
      { type: 'warning', confirmButtonText: '确认删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await adminApi.deleteItineraryDay(day.id)
    ElMessage.success('行程已删除')
    await load()
  } catch (cause) {
    if (cause.status === 409) ElMessage.warning(cause.message || '线路已上架，请先下架再调整行程结构')
    else if (cause.status !== 401) ElMessage.error(cause.message || '删除失败')
  }
}

// ---------------- 行程项目 ----------------

function openItemDialog(day, item) {
  loadOptions()
  itemError.value = ''
  itemDayId.value = day.id
  editingItemId.value = item?.id || null
  Object.assign(itemForm, {
    sortNo: item?.sortNo || nextSortNo(day),
    itemType: item?.itemType || 'ATTRACTION',
    name: item?.name || '',
    description: item?.description || '',
    attractionId: item?.attractionId || '',
    longitude: item?.longitude == null ? '' : item.longitude,
    latitude: item?.latitude == null ? '' : item.latitude
  })
  itemDialogVisible.value = true
}

function nextSortNo(day) {
  return (day.items || []).reduce((max, item) => Math.max(max, item.sortNo || 0), 0) + 1
}

/** 选择景点后自动带出经纬度，未填写坐标时与后端行为一致。 */
function applyAttractionCoordinates() {
  const attraction = attractions.value.find((item) => String(item.id) === String(itemForm.attractionId))
  if (!attraction) return
  if (itemForm.longitude === '' || itemForm.longitude == null) itemForm.longitude = attraction.longitude ?? ''
  if (itemForm.latitude === '' || itemForm.latitude == null) itemForm.latitude = attraction.latitude ?? ''
}

function coordinate(value) {
  if (value === '' || value == null) return null
  const number = Number(value)
  return Number.isFinite(number) ? number : null
}

async function saveItem() {
  if (itemSaving.value) return
  itemError.value = ''
  const sortNo = Number(itemForm.sortNo)
  const name = itemForm.name.trim()
  if (!Number.isInteger(sortNo) || sortNo < 1) return ElMessage.warning('排序号应为大于 0 的整数')
  if (!name) return ElMessage.warning('请填写行程项目名称')
  // 契约 ItineraryItemRequest 的 name 上限 200、description 上限 10000，单位是字符（码点）而不是
  // UTF-16 码元：一个 emoji 在 String#length 里记 2，会把契约允许的名称误判成超长。
  if (codePointLength(name) > 200) return ElMessage.warning('行程项目名称最多 200 个字符')
  const description = optional(itemForm.description)
  if (codePointLength(description) > 10000) return ElMessage.warning('行程项目说明最多 10000 个字符')

  // 经纬度必须成对（契约 CoordinatePairRule / 后端 @CoordinatePairComplete）：
  // 只填一个的点在用户端地图上会被静默丢弃。两个都留空是允许的，此时后端会整对继承关联景点的坐标。
  const longitude = coordinate(itemForm.longitude)
  const latitude = coordinate(itemForm.latitude)
  if ((longitude == null) !== (latitude == null)) {
    return ElMessage.warning('经度和纬度需要同时填写，或同时留空')
  }

  const payload = {
    sortNo,
    itemType: itemForm.itemType,
    name,
    description,
    attractionId: optional(itemForm.attractionId),
    longitude,
    latitude
  }

  itemSaving.value = true
  try {
    if (editingItemId.value) await adminApi.updateItineraryItem(editingItemId.value, payload)
    else await adminApi.createItineraryItem(itemDayId.value, payload)
    itemDialogVisible.value = false
    ElMessage.success(editingItemId.value ? '行程项目已更新' : '行程项目已新增')
    await load()
  } catch (cause) {
    itemError.value = cause.message || '行程项目保存失败'
  } finally {
    itemSaving.value = false
  }
}

async function removeItem(day, item) {
  try {
    await ElMessageBox.confirm(`确认删除行程项目「${item.name}」吗？`, '删除行程项目', {
      type: 'warning', confirmButtonText: '确认删除', cancelButtonText: '取消'
    })
  } catch {
    return
  }
  try {
    await adminApi.deleteItineraryItem(item.id)
    ElMessage.success('行程项目已删除')
    await load()
  } catch (cause) {
    if (cause.status !== 401) ElMessage.error(cause.message || '删除失败')
  }
}

onMounted(load)
</script>

<template>
  <div class="admin-route-detail">
    <div class="admin-page-head">
      <div>
        <PanelIconButton class="back-link" action="back" label="返回上一页" :fallback-to="{ name: 'admin-routes' }" />
        <h2>
          {{ routeInfo?.name || '线路详情' }}
          <span v-if="routeInfo" class="tag" :class="statusClasses[routeInfo.status] || ''">
            {{ statusNames[routeInfo.status] || routeInfo.status }}
          </span>
        </h2>
        <p v-if="routeInfo">线路编号 #{{ routeInfo.id }} · {{ routeInfo.departureCity }} → {{ routeInfo.destination }}</p>
      </div>
      <div v-if="routeInfo" class="head-actions">
        <button class="secondary-button" @click="editDialogVisible = true">编辑线路资料</button>
        <button class="primary-button" :disabled="statusUpdating" @click="toggleStatus">
          {{ isPublished ? '下架线路' : '上架线路' }}
        </button>
      </div>
    </div>

    <RequestState v-if="error" :error="error" @retry="load" />

    <div v-else-if="loading">
      <el-skeleton :rows="10" animated />
    </div>

    <template v-else-if="routeInfo">
      <section class="admin-panel">
        <h3 class="panel-title">线路基本资料</h3>
        <dl class="info-grid">
          <div><dt>出发城市</dt><dd>{{ routeInfo.departureCity }}</dd></div>
          <div><dt>目的地</dt><dd>{{ routeInfo.destination }}</dd></div>
          <div><dt>行程天数</dt><dd>{{ routeInfo.durationDays }} 天</dd></div>
          <div><dt>最低团期价</dt><dd class="amount">{{ routeInfo.minAdultPrice == null ? '暂无可售团期' : `¥${routeInfo.minAdultPrice}` }}</dd></div>
          <div><dt>最近出发</dt><dd>{{ routeInfo.nextDepartureDate || '暂无可售团期' }}</dd></div>
          <div><dt>可报名余位</dt><dd>{{ routeInfo.availableSeats == null ? '—' : `${routeInfo.availableSeats} 人` }}</dd></div>
          <div><dt>综合评分</dt><dd>{{ routeInfo.ratingCount ? `${routeInfo.ratingAvg}（${routeInfo.ratingCount} 条）` : '暂无评价' }}</dd></div>
          <div><dt>有效报名订单数</dt><dd>{{ routeInfo.validBookingCount }} 单</dd></div>
        </dl>
        <div class="text-blocks">
          <div><strong>线路简介</strong><p>{{ routeInfo.description || '未填写' }}</p></div>
          <div><strong>费用包含</strong><p>{{ routeInfo.included || '未填写' }}</p></div>
          <div><strong>费用不含</strong><p>{{ routeInfo.excluded || '未填写' }}</p></div>
          <div><strong>报名须知</strong><p>{{ routeInfo.bookingNotice || '未填写' }}</p></div>
        </div>
      </section>

      <section class="admin-panel">
        <h3 class="panel-title">团期概览（{{ departures.length }}）</h3>
        <table v-if="departures.length" class="data-table">
          <thead>
            <tr>
              <th>出发日期</th>
              <th>返程日期</th>
              <th>成人价</th>
              <th>儿童价</th>
              <th>剩余名额</th>
              <th>带团导游</th>
              <th>状态</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="departure in departures" :key="departure.id">
              <td>{{ departure.startDate }}</td>
              <td>{{ departure.endDate }}</td>
              <td class="amount">¥{{ departure.adultPrice }}</td>
              <td>¥{{ departure.childPrice }}</td>
              <td>{{ departure.availableSeats }} / {{ departure.maxPeople }} 人</td>
              <td>{{ departure.guideName || '未分配' }}</td>
              <td><span class="tag">{{ departureStatusNames[departure.status] || departure.status }}</span></td>
            </tr>
          </tbody>
        </table>
        <div v-else class="empty-box">该线路还没有团期，可前往「团期班次排期」创建。</div>
      </section>

      <section class="admin-panel">
        <div class="panel-head">
          <h3 class="panel-title">每日行程管理（{{ days.length }} 天）</h3>
          <button class="secondary-button" :disabled="isPublished" @click="openDayDialog(null)">
            + 新增一日行程
          </button>
        </div>
        <p v-if="isPublished" class="hint">线路已上架：行程结构（增删天数）已冻结，如需调整请先下架；仍可修正已存在行程的文案与项目内容。</p>

        <div v-if="days.length" class="day-list">
          <article v-for="day in days" :key="day.id" class="day-card">
            <header class="day-head">
              <div>
                <strong>第 {{ day.dayNumber }} 天 · {{ day.title }}</strong>
                <div class="day-meta">
                  <span>交通：{{ day.transportation || '未填写' }}</span>
                  <span>餐食：{{ day.meals || '未填写' }}</span>

                  <span>住宿：{{ accommodationSummary(day) }}</span>
                </div>
              </div>
              <div class="day-actions">
                <button type="button" class="text-button" @click="openItemDialog(day, null)">+ 新增项目</button>
                <span class="divider">|</span>
                <button type="button" class="text-button" @click="openDayDialog(day)">编辑</button>
                <span class="divider">|</span>
                <button type="button" class="text-button text-danger" :disabled="isPublished" @click="removeDay(day)">
                  删除
                </button>
              </div>
            </header>
            <p v-if="day.description" class="day-description">{{ day.description }}</p>

            <table v-if="day.items?.length" class="data-table inner-table">
              <thead>
                <tr>
                  <th style="width: 70px;">排序</th>
                  <th style="width: 90px;">类型</th>
                  <th>名称</th>
                  <th>说明</th>
                  <th style="width: 170px;">经纬度</th>
                  <th style="text-align: right; width: 130px;">操作</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="item in day.items" :key="item.id">
                  <td>{{ item.sortNo }}</td>
                  <td><span class="tag">{{ itemTypeNames[item.itemType] || item.itemType }}</span></td>
                  <td><strong>{{ item.name }}</strong></td>
                  <td>{{ item.description || '—' }}</td>
                  <td>{{ item.longitude == null || item.latitude == null ? '—' : `${item.longitude}, ${item.latitude}` }}</td>
                  <td style="text-align: right;">
                    <button type="button" class="text-button" @click="openItemDialog(day, item)">编辑</button>
                    <span class="divider">|</span>
                    <button type="button" class="text-button text-danger" @click="removeItem(day, item)">删除</button>
                  </td>
                </tr>
              </tbody>
            </table>
            <p v-else class="empty-inline">该天还没有行程项目，可点击“+ 新增项目”添加景点、交通或餐食安排。</p>
          </article>
        </div>
        <div v-else class="empty-box">
          该线路还没有每日行程。请先新增行程，再上架销售。
        </div>
      </section>

      <section class="admin-panel">
        <h3 class="panel-title">线路评价（{{ reviews.length }}）</h3>
        <table v-if="reviews.length" class="data-table">
          <thead>
            <tr>
              <th>订单号</th>
              <th>用户</th>
              <th>评分</th>
              <th>内容</th>
              <th>状态</th>
              <th>时间</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="review in reviews" :key="review.id">
              <td>{{ review.orderNo }}</td>
              <td>{{ review.userNickname || '—' }}</td>
              <td>{{ review.rating }} 分</td>
              <td>{{ review.content }}</td>
              <td><span class="tag">{{ review.status }}</span></td>
              <td>{{ review.createdAt }}</td>
            </tr>
          </tbody>
        </table>
        <div v-else class="empty-box">该线路暂无评价数据。</div>
      </section>
    </template>

    <RouteFormDialog v-model="editDialogVisible" :route="routeInfo" @saved="load" />

    <!-- 每日行程表单 -->
    <el-dialog
      append-to-body
      v-model="dayDialogVisible"
      :title="editingDayId ? '编辑每日行程' : '新增每日行程'"
      width="min(620px, calc(100vw - 32px))"
      :close-on-click-modal="!daySaving"
    >
      <div class="dialog-form-grid">
        <p v-if="dayError" class="form-error wide" role="alert">{{ dayError }}</p>
        <div class="form-field">
          <label>第几天 <span class="req">*</span></label>
          <input v-model.number="dayForm.dayNumber" type="number" min="1" />
        </div>

        <div class="form-field">
          <label>住宿安排 <span class="req">*</span></label>
          <select v-model="dayForm.accommodationType">
            <option value="HOTEL">指定酒店</option>
            <option value="STANDARD">只确定住宿标准</option>
            <option value="NONE">当天不含住宿</option>
            <option value="PENDING">住宿待确认</option>
          </select>
        </div>
        <div class="form-field">
          <label>所属酒店</label>
          <select v-model="dayForm.hotelId">
            <option value="">不指定</option>
            <option
              v-for="hotel in hotels"
              :key="hotel.id"
              :value="hotel.id"
              :disabled="isHotelDisabledOption(hotel)"
            >
              {{ hotel.name }}{{ isHotelDisabledOption(hotel) ? '（已停用）' : '' }}
            </option>
          </select>
          <p class="form-hint">选择酒店会把当天设为「指定酒店」；改成其它住宿安排时这里会被清空。</p>
        </div>
        <!-- 本弹窗所有文本框都不设 maxlength：它按 UTF-16 码元截断，会把契约允许的 emoji 文案静默砍短
             （ItineraryDayRequest：title 200 / description 10000 / transportation、meals 255），上限由 saveDay() 按码点校验 -->
        <div class="form-field wide">
          <label>行程标题 <span class="req">*</span></label>
          <input v-model="dayForm.title" placeholder="例如：上海 → 昆明" />
        </div>
        <div class="form-field wide">
          <label>行程说明</label>
          <textarea v-model="dayForm.description" rows="3" placeholder="当天安排说明"></textarea>
        </div>
        <div class="form-field">
          <label>交通说明</label>
          <input v-model="dayForm.transportation" placeholder="例如：飞机 / 旅游大巴" />
        </div>
        <div class="form-field">
          <label>餐食说明</label>
          <input v-model="dayForm.meals" placeholder="例如：早、午餐" />
        </div>

        <div v-if="dayForm.accommodationType !== 'NONE'" class="form-field wide">
          <label>住宿标准<template v-if="dayForm.accommodationType === 'STANDARD'"> <span class="req">*</span></template></label>
          <input v-model="dayForm.accommodationStandard" placeholder="例如：市区舒适型酒店（有可靠依据时才写星级）" />
          <p class="form-hint">
            指定酒店与只确定标准时都可以填写；「住宿待确认」下也可以先记录已经谈定的标准。
          </p>
        </div>

        <template v-if="dayForm.accommodationType !== 'NONE'">
          <div class="form-field">
            <label>房型</label>
            <input v-model="dayForm.roomType" placeholder="例如：双床房" />
          </div>
          <div class="form-field">
            <label>早餐</label>
            <select v-model="dayForm.breakfastIncluded">
              <option value="">未说明</option>
              <option value="true">含早餐</option>
              <option value="false">不含早餐</option>
            </select>
            <p class="form-hint">这里指本线路当天的住宿是否含早餐，与酒店自身的早餐服务无关。</p>
          </div>
        </template>
        <div class="form-field wide">
          <label>住宿说明</label>
          <textarea v-model="dayForm.accommodationNote" rows="2" placeholder="例如：拼房安排、夜车返程不含住宿、入住说明等"></textarea>
        </div>
      </div>
      <template #footer>
        <button class="secondary-button" :disabled="daySaving" @click="dayDialogVisible = false">取消</button>
        <button class="primary-button" :disabled="daySaving" @click="saveDay">
          {{ daySaving ? '保存中…' : '保存行程' }}
        </button>
      </template>
    </el-dialog>

    <!-- 行程项目表单 -->
    <el-dialog
      append-to-body
      v-model="itemDialogVisible"
      :title="editingItemId ? '编辑行程项目' : '新增行程项目'"
      width="min(620px, calc(100vw - 32px))"
      :close-on-click-modal="!itemSaving"
    >
      <div class="dialog-form-grid">
        <p v-if="itemError" class="form-error wide" role="alert">{{ itemError }}</p>
        <div class="form-field">
          <label>排序号 <span class="req">*</span></label>
          <input v-model.number="itemForm.sortNo" type="number" min="1" />
        </div>
        <div class="form-field">
          <label>项目类型 <span class="req">*</span></label>
          <select v-model="itemForm.itemType">
            <option value="ATTRACTION">景点</option>
            <option value="TRANSPORT">交通</option>
            <option value="MEAL">餐食</option>
            <option value="ACTIVITY">活动</option>
            <option value="OTHER">其他</option>
          </select>
        </div>
        <!-- 同样不设 maxlength（ItineraryItemRequest：name 200 / description 10000），由 saveItem() 按码点校验 -->
        <div class="form-field wide">
          <label>项目名称 <span class="req">*</span></label>
          <input v-model="itemForm.name" placeholder="例如：大理古城" />
        </div>
        <div class="form-field wide">
          <label>关联景点</label>
          <select v-model="itemForm.attractionId" @change="applyAttractionCoordinates">
            <option value="">不关联</option>
            <option v-for="attraction in attractions" :key="attraction.id" :value="attraction.id">
              {{ attraction.name }}（{{ attraction.city }}）
            </option>
          </select>
        </div>
        <div class="form-field">
          <label>经度</label>
          <input v-model="itemForm.longitude" type="number" step="0.0000001" placeholder="-180 ~ 180" />
        </div>
        <div class="form-field">
          <label>纬度</label>
          <input v-model="itemForm.latitude" type="number" step="0.0000001" placeholder="-90 ~ 90" />
        </div>
        <div class="form-field wide">
          <label>项目说明</label>
          <textarea v-model="itemForm.description" rows="2" placeholder="游览安排说明"></textarea>
        </div>
      </div>
      <template #footer>
        <button class="secondary-button" :disabled="itemSaving" @click="itemDialogVisible = false">取消</button>
        <button class="primary-button" :disabled="itemSaving" @click="saveItem">
          {{ itemSaving ? '保存中…' : '保存项目' }}
        </button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.admin-page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 18px;
}

.admin-page-head h2 {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 6px 0 4px;
  font-size: 18px;
}

.admin-page-head p {
  margin: 0;
  color: var(--text-secondary);
  font-size: 13px;
}

.back-link {
  margin-bottom: 8px;
}

.head-actions {
  display: flex;
  gap: 10px;
}

.panel-title {
  margin: 0 0 14px;
  font-size: 15px;
}

.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.hint {
  margin: 0 0 14px;
  font-size: 12px;
  color: var(--status-orange, #b45309);
}

.info-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
  gap: 12px 18px;
  margin: 0 0 16px;
}

.info-grid dt {
  font-size: 12px;
  color: var(--text-tertiary);
  margin-bottom: 2px;
}

.info-grid dd {
  margin: 0;
  font-weight: 600;
}

.text-blocks {
  display: grid;
  gap: 10px;
  border-top: 1px solid var(--border-divider);
  padding-top: 14px;
}

.text-blocks strong {
  font-size: 12px;
  color: var(--text-tertiary);
}

.text-blocks p {
  margin: 2px 0 0;
  color: var(--text-secondary);
  font-size: 13px;
  white-space: pre-wrap;
}

.day-list {
  display: grid;
  gap: 14px;
}

.day-card {
  border: 1px solid var(--border-divider);
  border-radius: 12px;
  padding: 14px;
  background: var(--bg-subtle, #fafafa);
}

.day-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.day-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin-top: 4px;
  font-size: 12px;
  color: var(--text-secondary);
}

.day-description {
  margin: 8px 0 0;
  font-size: 13px;
  color: var(--text-secondary);
}

.day-actions {
  white-space: nowrap;
}

.inner-table {
  margin-top: 12px;
  background: white;
  border-radius: 10px;
}

.empty-inline {
  margin: 10px 0 0;
  font-size: 12px;
  color: var(--text-tertiary);
}

.amount {
  color: var(--price-orange);
  font-weight: 700;
}

.divider {
  color: var(--border-strong);
  margin: 0 6px;
  font-size: 11px;
}

.text-danger {
  color: var(--status-red) !important;
}

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

@media (max-width: 640px) {
  .dialog-form-grid {
    grid-template-columns: 1fr;
  }

  .admin-page-head {
    flex-direction: column;
  }
}
</style>
