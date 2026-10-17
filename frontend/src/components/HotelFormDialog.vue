<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { adminApi } from '@/api/modules'
import { FACILITY_VALUES, facilityLabel } from '@/utils/hotel'
import { codePointLength, overCodePoints } from '@/utils/text'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  hotel: { type: Object, default: null }
})
const emit = defineEmits(['update:modelValue', 'saved'])

const submitting = ref(false)
const formError = ref('')

// Only submit status when explicitly changed to preserve concurrent updates.
const originalStatus = ref('ACTIVE')

const baseVersion = ref(null)

const conflictMessage = ref('')
const conflictLatest = ref(null)
const conflictLoading = ref(false)

const EDITABLE_FIELD_LABELS = {
  name: '酒店名称',
  city: '城市',
  coverUrl: '封面图',
  images: '酒店图片',
  starRating: '官方星级',
  facilities: '酒店设施',
  checkInTime: '入住时间',
  checkOutTime: '退房时间',
  address: '详细地址',
  contactPhone: '联系电话',
  status: '运营状态',
  longitude: '经度',
  latitude: '纬度',
  intro: '酒店简介',
  dataSource: '数据来源说明'
}

const form = reactive({
  name: '',
  city: '',
  address: '',
  contactPhone: '',
  coverUrl: '',
  starRating: '',
  facilities: [],
  checkInTime: '',
  checkOutTime: '',
  images: [],
  longitude: '',
  latitude: '',
  intro: '',
  dataSource: '',
  status: 'ACTIVE'
})

function reset() {
  applyHotel(props.hotel || {})
  formError.value = ''
  conflictMessage.value = ''
  conflictLatest.value = null
  conflictLoading.value = false
}

watch(() => [props.modelValue, props.hotel], () => {
  if (props.modelValue) reset()
}, { immediate: true })

function optional(value) {
  const text = String(value ?? '').trim()
  return text === '' ? null : text
}

// Count Unicode code points; HTML maxlength counts UTF-16 units.
const NAME_MAX = 128
const CITY_MAX = 64
const ADDRESS_MAX = 255
const CONTACT_PHONE_MAX = 20
const INTRO_MAX = 10000
const DATA_SOURCE_MAX = 500
const IMAGE_URL_MAX = 500
const IMAGE_ALT_MAX = 200

const IMAGES_MAX = 10

const CLOCK_TIME_RE = /^([01]\d|2[0-3]):[0-5]\d$/

const IMAGE_URL_RE = /^https?:\/\/\S+$/

const STAR_RATINGS = ['1', '2', '3', '4', '5']

function coordinate(value, min, max, label) {
  const text = String(value ?? '').trim()
  if (text === '') return { value: null }
  const number = Number(text)
  if (!Number.isFinite(number)) return { error: `${label}应为数字` }
  if (number < min || number > max) return { error: `${label}应在 ${min} 到 ${max} 之间` }
  return { value: number }
}

function imageUrl(value, label) {
  const text = String(value ?? '').trim()
  if (text === '') return { value: null }
  if (overCodePoints(text, IMAGE_URL_MAX)) return { error: `${label}最多 ${IMAGE_URL_MAX} 个字符` }
  if (!IMAGE_URL_RE.test(text)) return { error: `${label}必须是 http:// 或 https:// 开头的完整地址` }
  return { value: text }
}

function starRatingValue() {
  const text = String(form.starRating ?? '').trim()
  if (text === '') return null
  const number = Number(text)
  return Number.isInteger(number) && number >= 1 && number <= 5 ? number : null
}

function clockTime(value, label) {
  const text = String(value ?? '').trim()
  if (text === '') return { value: null }
  if (!CLOCK_TIME_RE.test(text)) return { error: `${label}请按 24 小时制 HH:mm 填写，例如 14:00` }
  return { value: text }
}

function imagePayload() {
  return form.images.map((image, index) => ({
    url: String(image.url ?? '').trim(),
    alt: optional(image.alt),
    sortOrder: Number(image.sortOrder)
  }))
}

function validate() {
  const name = form.name.trim()
  const dataSource = form.dataSource.trim()
  if (!name) return '请填写酒店名称'
  if (overCodePoints(name, NAME_MAX)) return `酒店名称最多 ${NAME_MAX} 个字符`
  if (!form.city.trim()) return '请填写城市；旧酒店资料需要补录城市后才能保存'
  if (overCodePoints(form.city.trim(), CITY_MAX)) return `城市最多 ${CITY_MAX} 个字符`
  if (overCodePoints(form.address.trim(), ADDRESS_MAX)) return `酒店地址最多 ${ADDRESS_MAX} 个字符`
  if (overCodePoints(form.contactPhone.trim(), CONTACT_PHONE_MAX)) return `联系电话最多 ${CONTACT_PHONE_MAX} 个字符`
  if (overCodePoints(form.intro, INTRO_MAX)) return `酒店简介最多 ${INTRO_MAX} 个字符`
  if (!dataSource) return '请填写数据来源说明'
  if (overCodePoints(dataSource, DATA_SOURCE_MAX)) return `数据来源说明最多 ${DATA_SOURCE_MAX} 个字符`
  const cover = imageUrl(form.coverUrl, '封面图地址')
  if (cover.error) return cover.error
  const checkIn = clockTime(form.checkInTime, '入住时间')
  if (checkIn.error) return checkIn.error
  const checkOut = clockTime(form.checkOutTime, '退房时间')
  if (checkOut.error) return checkOut.error
  if (form.images.length > IMAGES_MAX) return `酒店图片最多 ${IMAGES_MAX} 张`
  for (const [index, image] of form.images.entries()) {
    const position = `第 ${index + 1} 张图片`
    const url = imageUrl(image.url, `${position}的地址`)
    if (url.error) return url.error
    if (url.value === null) return `${position}还没有填写地址`
    if (overCodePoints(image.alt, IMAGE_ALT_MAX)) return `${position}的说明最多 ${IMAGE_ALT_MAX} 个字符`
    if (!Number.isInteger(Number(image.sortOrder)) || Number(image.sortOrder) < 1) {
      return `${position}的展示顺序应为大于 0 的整数`
    }
  }
  const longitude = coordinate(form.longitude, -180, 180, '经度')
  if (longitude.error) return longitude.error
  const latitude = coordinate(form.latitude, -90, 90, '纬度')
  if (latitude.error) return latitude.error
  if ((longitude.value == null) !== (latitude.value == null)) {
    return '经度和纬度需要同时填写，或同时留空'
  }
  return ''
}

function addImage() {
  if (form.images.length >= IMAGES_MAX) return
  form.images.push({ url: '', alt: '', sortOrder: form.images.length + 1 })
}

function removeImage(index) {
  form.images.splice(index, 1)
}

function toggleFacility(value) {
  const index = form.facilities.indexOf(value)
  if (index === -1) form.facilities.push(value)
  else form.facilities.splice(index, 1)
}

function shouldSubmitStatus() {
  if (!props.hotel?.id) return true
  return form.status !== originalStatus.value
}

function close() {
  emit('update:modelValue', false)
}

function comparableHotel(source) {
  return {
    name: String(source.name || '').trim(),
    city: String(source.city || '').trim(),
    address: optional(source.address),
    contactPhone: optional(source.contactPhone),
    coverUrl: optional(source.coverUrl),
    images: (source.images || []).map((image) => ({
      url: String(image.url || '').trim(), alt: optional(image.alt), sortOrder: Number(image.sortOrder)
    })).sort((a, b) => a.sortOrder - b.sortOrder),
    starRating: source.starRating === '' || source.starRating == null ? null : Number(source.starRating),
    facilities: [...(source.facilities || [])].sort(),
    checkInTime: optional(source.checkInTime),
    checkOutTime: optional(source.checkOutTime),
    status: source.status === 'DISABLED' ? 'DISABLED' : 'ACTIVE',
    longitude: source.longitude === '' || source.longitude == null ? null : Number(source.longitude),
    latitude: source.latitude === '' || source.latitude == null ? null : Number(source.latitude),
    intro: optional(source.intro),
    dataSource: String(source.dataSource || '').trim()
  }
}
function displayConflictValue(key, value) {
  if (value == null || value === '') return '（空）'
  if (key === 'status') return value === 'DISABLED' ? '停用' : '启用'
  if (key === 'starRating') return value + ' 星'
  if (key === 'facilities') return value.map((item) => facilityLabel(item) || item).join('、') || '（空）'
  if (key === 'images') return value.map((image) =>
    image.sortOrder + '. ' + image.url + (image.alt ? '（' + image.alt + '）' : '')).join('\n') || '（空）'
  return String(value)
}
const conflictRows = computed(() => {
  if (!conflictLatest.value) return []
  const server = comparableHotel(conflictLatest.value)
  const mine = comparableHotel(form)
  return Object.keys(EDITABLE_FIELD_LABELS)
    .filter((key) => JSON.stringify(server[key]) !== JSON.stringify(mine[key]))
    .map((key) => ({ key, label: EDITABLE_FIELD_LABELS[key],
      server: displayConflictValue(key, server[key]), mine: displayConflictValue(key, mine[key]) }))
})

async function loadLatest() {
  const hotelId = props.hotel?.id
  if (!hotelId) return
  conflictLoading.value = true
  try {
    conflictLatest.value = (await adminApi.hotel(hotelId)) || null
  } catch {
    conflictLatest.value = null
  } finally {
    conflictLoading.value = false
  }
}

function adoptLatest() {
  if (!conflictLatest.value) return
  const latest = conflictLatest.value
  applyHotel(latest)
  conflictMessage.value = ''
  conflictLatest.value = null
  ElMessage.success('已载入服务器最新资料，请确认后再保存')
}

async function overwriteLatest() {
  if (!conflictLatest.value) return
  baseVersion.value = conflictLatest.value.version ?? baseVersion.value
  conflictMessage.value = ''
  conflictLatest.value = null
  await save()
}

function applyHotel(source) {
  const status = source?.status === 'DISABLED' ? 'DISABLED' : 'ACTIVE'
  Object.assign(form, {
    name: source?.name || '',
    city: source?.city || '',
    address: source?.address || '',
    contactPhone: source?.contactPhone || '',
    coverUrl: source?.coverUrl || '',
    starRating: source?.starRating == null ? '' : String(source.starRating),
    facilities: (Array.isArray(source?.facilities) ? source.facilities : [])
      .filter((value) => FACILITY_VALUES.includes(value)),
    checkInTime: source?.checkInTime || '',
    checkOutTime: source?.checkOutTime || '',
    images: (Array.isArray(source?.images) ? source.images : []).map((image, index) => ({
      url: image?.url || '',
      alt: image?.alt || '',
      sortOrder: image?.sortOrder ?? index + 1
    })),
    longitude: source?.longitude === null || source?.longitude === undefined ? '' : String(source.longitude),
    latitude: source?.latitude === null || source?.latitude === undefined ? '' : String(source.latitude),
    intro: source?.intro || '',
    dataSource: source?.dataSource || '',
    status
  })
  originalStatus.value = status
  baseVersion.value = source?.id ? source.version ?? baseVersion.value : null
}

async function save() {
  if (submitting.value) return
  formError.value = ''
  const invalid = validate()
  if (invalid) return ElMessage.warning(invalid)

  const editing = Boolean(props.hotel?.id)
  const payload = {
    name: form.name.trim(),
    city: form.city.trim(),
    address: optional(form.address),
    contactPhone: optional(form.contactPhone),
    coverUrl: imageUrl(form.coverUrl, '封面图地址').value,
    images: imagePayload(),
    starRating: starRatingValue(),
    facilities: [...form.facilities],
    checkInTime: clockTime(form.checkInTime, '入住时间').value,
    checkOutTime: clockTime(form.checkOutTime, '退房时间').value,
    longitude: coordinate(form.longitude, -180, 180, '经度').value,
    latitude: coordinate(form.latitude, -90, 90, '纬度').value,
    intro: optional(form.intro),
    dataSource: form.dataSource.trim(),
    ...(shouldSubmitStatus() ? { status: form.status } : {}),
    ...(editing ? { version: baseVersion.value } : {})
  }

  submitting.value = true
  try {
    const saved = editing
      ? await adminApi.updateHotel(props.hotel.id, payload)
      : await adminApi.createHotel(payload)
    if (saved?.status === 'ACTIVE' || saved?.status === 'DISABLED') {
      originalStatus.value = saved.status
    }
    if (saved?.version !== null && saved?.version !== undefined) {
      baseVersion.value = saved.version
    }
    ElMessage.success(editing ? '酒店资料已更新' : '酒店资料已新增')
    emit('saved', { hotel: saved, created: !editing })
    close()
  } catch (cause) {
    if (editing && cause.status === 409 && cause.code === 'HOTEL_VERSION_CONFLICT') {
      conflictMessage.value = cause.message || '这份酒店资料已被他人修改'
      conflictLatest.value = null
      await loadLatest()
      return
    }
    formError.value = cause.message || '酒店资料保存失败，请稍后重试'
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-dialog
    append-to-body
    class="hotel-form-dialog"
    :model-value="modelValue"
    :title="hotel?.id ? '编辑酒店资料' : '新增酒店资料'"
    width="min(680px, calc(100vw - 32px))"
    :close-on-click-modal="!submitting"
    :show-close="!submitting"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div class="dialog-form-grid">
      <p v-if="formError" class="form-error wide" role="alert">{{ formError }}</p>

      <div v-if="conflictMessage" class="conflict-panel wide" role="alert">
        <p class="conflict-title">{{ conflictMessage }}</p>
        <p class="conflict-note">你填写的内容已保留，没有被丢弃。</p>
        <el-skeleton v-if="conflictLoading" :rows="3" animated />
        <template v-else-if="conflictLatest">
          <p v-if="conflictRows.length" class="conflict-note conflict-diff">
            与服务器不一致的字段：
            <strong>{{ conflictRows.map((row) => row.label).join('、') }}</strong>；
            服务器当前版本 {{ conflictLatest.version }}。
          </p>
          <p v-else class="conflict-note conflict-diff">你填写的各项与服务器当前值一致。</p>
          <dl class="conflict-grid">
            <div v-for="row in conflictRows" :key="row.key" class="conflict-row-differs">
              <dt>{{ row.label }}</dt>
              <dd>
                <span class="conflict-server">服务器：{{ row.server }}</span>
                <span class="conflict-mine">你填写：{{ row.mine }}</span>
              </dd>
            </div>
          </dl>
        </template>
        <p v-else class="conflict-note">
          暂时取不到服务器最新资料（可能已被他人删除，或接口暂时不可用），请关闭后重新打开表单再试。
        </p>
        <div class="conflict-actions">
          <button type="button" class="secondary-button" :disabled="!conflictLatest" @click="adoptLatest">
            载入服务器最新数据
          </button>
          <button
            type="button"
            class="primary-button"
            :disabled="!conflictLatest || submitting"
            @click="overwriteLatest"
          >
            保留我的修改并覆盖
          </button>
        </div>
      </div>

      <div class="form-field wide">
        <label>酒店名称 <span class="req">*</span></label>
        <input v-model="form.name" placeholder="例如：大理古城演示酒店" />
        <p class="form-counter" :class="{ over: overCodePoints(form.name, NAME_MAX) }">
          {{ codePointLength(form.name) }} / {{ NAME_MAX }}
        </p>
      </div>

      <div class="form-field">
        <label>城市 <span class="req">*</span></label>
        <input v-model="form.city" placeholder="例如：杭州" />
        <p class="form-counter" :class="{ over: overCodePoints(form.city, CITY_MAX) }">
          {{ codePointLength(form.city) }} / {{ CITY_MAX }}
        </p>
        <p class="form-hint">
          街道门牌写在「详细地址」里。城市必填，旧资料若还没有城市，请补录后保存。
        </p>
      </div>

      <div class="form-field">
        <label>联系电话</label>
        <input v-model="form.contactPhone" placeholder="例如：0872-1234567" />
        <p class="form-counter" :class="{ over: overCodePoints(form.contactPhone, CONTACT_PHONE_MAX) }">
          {{ codePointLength(form.contactPhone) }} / {{ CONTACT_PHONE_MAX }}
        </p>
        <p class="form-hint">仅供后台联系酒店使用，不会展示在用户端酒店详情中。</p>
      </div>

      <div class="form-field">
        <label>运营状态</label>
        <select v-model="form.status">
          <option value="ACTIVE">启用（可用于行程）</option>
          <option value="DISABLED">停用（不再安排进新行程）</option>
        </select>
      </div>

      <div class="form-field">
        <label>官方星级</label>
        <select v-model="form.starRating">
          <option value="">未提供（没有可靠依据）</option>
          <option v-for="star in STAR_RATINGS" :key="star" :value="star">{{ star }} 星</option>
        </select>
        <p class="form-hint">官方星级与网站评分、"几钻"不是一回事，只填写有可靠依据的星级。</p>
      </div>

      <div class="form-field wide">
        <label>详细地址</label>
        <input v-model="form.address" placeholder="例如：云南省大理白族自治州大理市" />
        <p class="form-counter" :class="{ over: overCodePoints(form.address, ADDRESS_MAX) }">
          {{ codePointLength(form.address) }} / {{ ADDRESS_MAX }}
        </p>
      </div>

      <div class="form-field wide">
        <label>封面图地址</label>
        <input v-model="form.coverUrl" placeholder="例如：https://example.com/hotel-cover.jpg" />
        <p class="form-counter" :class="{ over: overCodePoints(form.coverUrl, IMAGE_URL_MAX) }">
          {{ codePointLength(form.coverUrl) }} / {{ IMAGE_URL_MAX }}
        </p>
        <p class="form-hint">
          用于线路每日行程的酒店卡片与酒店详情页；留空时用户端显示占位图，不会自动改用详情图片。
          必须是 http:// 或 https:// 开头的完整地址（项目不提供图片上传服务）。
        </p>
      </div>

      <div class="form-field">
        <label>经度</label>
        <input v-model="form.longitude" inputmode="decimal" placeholder="例如：100.1650000" />
      </div>

      <div class="form-field">
        <label>纬度</label>
        <input v-model="form.latitude" inputmode="decimal" placeholder="例如：25.6940000" />
      </div>

      <div class="form-field">
        <label>入住时间</label>
        <input v-model="form.checkInTime" inputmode="numeric" placeholder="例如：14:00" />
        <p class="form-hint">24 小时制 HH:mm，例如 14:00；不清楚时留空。</p>
      </div>

      <div class="form-field">
        <label>退房时间</label>
        <input v-model="form.checkOutTime" inputmode="numeric" placeholder="例如：12:00" />
        <p class="form-hint">24 小时制 HH:mm，例如 12:00；不清楚时留空。</p>
      </div>

      <div class="form-field wide">
        <label>酒店设施</label>
        <div class="facility-grid">
          <label v-for="value in FACILITY_VALUES" :key="value" class="facility-option">
            <input
              type="checkbox"
              :value="value"
              :checked="form.facilities.includes(value)"
              @change="toggleFacility(value)"
            />
            <span>{{ facilityLabel(value) }}</span>
          </label>
        </div>
        <p class="form-hint">
          已选 {{ form.facilities.length }} 项。这里的「早餐服务」只表示酒店自身提供早餐服务，
          与某条线路当天是否含早餐是两回事，互不推断。
        </p>
      </div>

      <div class="form-field wide">
        <label>酒店简介</label>
        <textarea v-model="form.intro" rows="3" placeholder="用于线路行程展示的酒店介绍文字"></textarea>
        <p class="form-counter" :class="{ over: overCodePoints(form.intro, INTRO_MAX) }">
          {{ codePointLength(form.intro) }} / {{ INTRO_MAX }}
        </p>
      </div>

      <div class="form-field wide">
        <label>酒店图片（最多 {{ IMAGES_MAX }} 张）</label>
        <div v-if="form.images.length" class="image-rows">
          <div v-for="(image, index) in form.images" :key="index" class="image-row">
            <input v-model="image.url" class="image-url" :placeholder="`第 ${index + 1} 张：https://example.com/hotel-${index + 1}.jpg`" aria-label="图片地址" />
            <input v-model="image.alt" class="image-alt" placeholder="图片说明（可选，供读屏与加载失败时展示）" aria-label="图片说明" />
            <input v-model.number="image.sortOrder" class="image-sort" type="number" min="1" aria-label="展示顺序" />
            <button type="button" class="text-button text-danger" @click="removeImage(index)">删除</button>
          </div>
        </div>
        <p v-else class="form-hint">还没有图片。用户端详情页按展示顺序（升序）排列这些图片。</p>
        <button type="button" class="secondary-button" :disabled="form.images.length >= IMAGES_MAX" @click="addImage">
          + 添加图片
        </button>
        <p class="form-counter" :class="{ over: form.images.length > IMAGES_MAX }">
          {{ form.images.length }} / {{ IMAGES_MAX }}
        </p>
      </div>

      <div class="form-field wide">
        <label>数据来源说明 <span class="req">*</span></label>
        <input v-model="form.dataSource" placeholder="例如：团队整理的测试数据；坐标仅用于软件演示" />
        <p class="form-counter" :class="{ over: overCodePoints(form.dataSource, DATA_SOURCE_MAX) }">
          {{ codePointLength(form.dataSource) }} / {{ DATA_SOURCE_MAX }}
        </p>
        <p class="form-hint">
          资料必须可追溯：来源说明会随酒店一起保存，供后台核对，并作为用户端的「资料来源」展示，不得留空。
        </p>
      </div>

      <p class="form-hint wide">
        经纬度用于登记酒店的 WGS-84 位置，供资料核对与后续扩展；用户端地图只标注每日行程项目
        与地点指南的景点坐标，<strong>酒店不参与地图标注</strong>。未填写坐标的酒店仍可保存并安排进行程。
        酒店只作为行程资源使用，本项目不提供酒店订单、库存、房型销售与单独下单。
        停用只是把资料标记为不再使用：既不删除资料，也不改动已引用它的行程，但停用后这家酒店
        不能再被安排进新的每日行程；删除则要求没有任何行程还在引用该酒店，否则后端会拒绝并提示被线路行程占用。
      </p>
    </div>

    <template #footer>
      <button class="secondary-button" :disabled="submitting" @click="close">取消</button>
      <button class="primary-button" :disabled="submitting" @click="save">
        {{ submitting ? '保存中…' : '保存酒店' }}
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

.facility-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(140px, 1fr));
  gap: 6px 12px;
}

.facility-option {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: var(--text-primary);
  cursor: pointer;
}

.image-rows {
  display: grid;
  gap: 8px;
}

.image-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 160px) 72px auto;
  gap: 8px;
  align-items: center;
}

.image-sort {
  text-align: center;
}

@media (max-width: 640px) {
  .image-row {
    grid-template-columns: 1fr;
  }
}

.form-hint {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--text-secondary);
}

.form-counter {
  margin: 4px 0 0;
  font-size: 12px;
  text-align: right;
  color: var(--text-tertiary);
}

.form-counter.over {
  color: var(--danger-red);
  font-weight: 700;
}

.conflict-panel {
  display: grid;
  gap: 8px;
  border: 1px solid var(--status-orange, #f59e0b);
  border-radius: 10px;
  padding: 12px;
  background: var(--bg-warning-subtle, #fffbeb);
}

.conflict-title {
  margin: 0;
  font-size: 13px;
  font-weight: 600;
  color: var(--status-orange-strong, #b45309);
}

.conflict-note {
  margin: 0;
  font-size: 12px;
  color: var(--text-secondary);
}

.conflict-diff strong {
  color: var(--danger-red);
}

.conflict-grid {
  display: grid;
  gap: 6px;
  margin: 0;
}

.conflict-grid dt {
  font-size: 12px;
  color: var(--text-tertiary);
}

.conflict-grid dd {
  overflow-wrap: anywhere;
  white-space: pre-wrap;
  display: grid;
  gap: 2px;
  margin: 0;
  font-size: 13px;
}

.conflict-row-differs dt {
  color: var(--danger-red);
  font-weight: 700;
}

.conflict-server {
  color: var(--text-primary);
}

.conflict-mine {
  color: var(--text-secondary);
}

.conflict-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

@media (max-width: 640px) {
  .dialog-form-grid {
    grid-template-columns: 1fr;
  }
}
</style>

<style>
@media (max-width: 640px) {
  .hotel-form-dialog { margin-top: 5dvh; }
  .hotel-form-dialog .el-dialog__body { max-height: 65dvh; overflow-y: auto; }
}
</style>
