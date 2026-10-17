<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { adminApi } from '@/api/modules'
import { fetchAllPages } from '@/utils/paging'
import { codePointLength } from '@/utils/text'

/**
 * 地点指南新增 / 修改表单弹窗（对应契约 POST /admin/place-guides 与 PUT /admin/place-guides/{guideId}）。
 *
 * <p>契约 {@code PlaceGuideUpsertRequest} 只接受 {@code title / summary / city / destination / coverUrl / places}：
 * {@code id / status / authorId / publishedAt} 都不由本表单提交 —— 主键与发布时间由后端维护，
 * 而 {@code status}（发布 / 下线）是独立的 {@code PATCH /admin/place-guides/{guideId}/status} 端点，
 * 由列表里的按钮触发，不混进资料表单（提交这些字段会被后端严格模式拒绝）。</p>
 *
 * <p>{@code places} 是 <b>2–50 个</b>、且不能重复的地点，顺序即地图上的 {@code sortOrder}。
 * 后端还会校验每个地点必须是<b>带坐标的已启用景点</b>（否则地图无法聚焦），
 * 因此这里只把"启用且有经纬度"的景点列成可选项，并在提交前就地拦一次，
 * 避免运营白等一个必然 422 的请求。</p>
 *
 * <p>文本长度按 Unicode 码点校验（与契约 {@code maxLength} 同口径），因此输入框
 * <b>不设 maxlength</b>：HTML 的 maxlength 数的是 UTF-16 码元，会把契约允许的文案静默截短。</p>
 *
 * 保存成功后 emit {@code saved}，载荷 {@code { guide, created }}：{@code guide} 是后端返回的指南，
 * {@code created} 表示这次走的是 POST（新建）还是 PUT（修改），由调用方决定刷新哪一页。
 */
const props = defineProps({
  modelValue: { type: Boolean, default: false },
  guide: { type: Object, default: null }
})
const emit = defineEmits(['update:modelValue', 'saved'])

const submitting = ref(false)
const formError = ref('')

const TITLE_MIN = 2
const TITLE_MAX = 200
const SUMMARY_MAX = 500
const CITY_MAX = 64
const DESTINATION_MAX = 128
const COVER_MAX = 500
const NOTE_MAX = 500
const MIN_PLACES = 2
const MAX_PLACES = 50

const form = reactive({
  title: '',
  summary: '',
  city: '',
  destination: '',
  coverUrl: ''
})

/** 地点清单：每项 { attractionId, note }，数组顺序即地图顺序（后端映射为 sortOrder）。 */
const places = ref([])

/** 候选景点：只取启用且有坐标的，逐页取全量（契约 size 上限 100）。 */
const attractionOptions = ref([])
const optionsLoading = ref(false)
/** 候选景点取数失败的原因；失败后必须让运营能重试，而不是整个会话都卡在空候选上。 */
const optionsError = ref('')
/** 正在进行中的候选取数：弹窗反复开关时不重复发同一批请求。 */
let optionsRequest = null

const editing = computed(() => Boolean(props.guide?.id))

/** 有坐标且启用的景点才可被选为指南地点（后端以 422 拒绝"无坐标 / 已停用"的地点）。 */
const hasCoordinates = (attraction) =>
  attraction?.longitude != null && attraction?.latitude != null

/**
 * 取候选景点。
 *
 * <p><b>每次打开弹窗都重新取，不做跨次缓存。</b>景点资料是共用资源：别的成员可能刚给某个景点
 * 补齐坐标、新建景点或停用景点；同一个会话里先去「景点资料库」补完坐标再回到本页，浏览器
 * 也不会刷新。缓存住首次结果的话，这些景点在下拉里根本不存在，页面会把其实合法的地点标成
 * "已停用或缺坐标，请替换"，保存又被本地校验拦下，而且关闭重开依然无效 —— 只能刷新整页。</p>
 *
 * <p>取数失败时<b>保留上一次成功的结果</b>，不把候选清空：宁可让运营继续用略旧的候选列表
 * （提交时后端仍按真实数据校验，不合法会回 422 并给出可读提示），也不要在一次网络抖动之后
 * 让整份候选消失、连改个标题都保存不了。从未取到过候选时列表本来就是空的，
 * 这时由 {@code optionsError} 就地说明原因。</p>
 */
function loadOptions() {
  if (optionsRequest) return optionsRequest
  optionsLoading.value = true
  optionsError.value = ''
  optionsRequest = (async () => {
    try {
      const all = await fetchAllPages(adminApi.attractions)
      attractionOptions.value = all.filter((item) => item.status === 'ACTIVE' && hasCoordinates(item))
    } catch {
      // 错误提示已由 axios 拦截器弹出；这里再给一句可就地重试的说明。
      optionsError.value = '候选景点加载失败，请关闭后重新打开本弹窗重试。'
    } finally {
      optionsLoading.value = false
      optionsRequest = null
    }
  })()
  return optionsRequest
}

/**
 * 某个景点能否出现在下拉里：启用且有坐标，或者是"本次已选中的地点"。
 *
 * 例外很重要——已发布的指南若引用了后来被停用 / 清空坐标的景点，直接把它从下拉里拿掉，
 * 编辑这条指南时下拉会变成空的，连改个标题都会因为"地点不合法"保存失败，运营无从下手。
 * 保留它并标注原因，让运营看到问题、自行替换。
 */
function isSelectable(attractionId, rowIndex) {
  if (places.value.some((place, index) => index !== rowIndex && String(place.attractionId) === String(attractionId))) {
    return false
  }
  const attraction = attractionOptions.value.find((item) => String(item.id) === String(attractionId))
  return Boolean(attraction) || places.value[rowIndex]?.attractionId === attractionId
}

/** 下拉里展示的选项：全部可编辑景点，加上当前已选中但已失格的景点（带警示）。 */
const selectableAttractions = computed(() => {
  const list = [...attractionOptions.value]
  for (const place of places.value) {
    if (!place.attractionId) continue
    if (!list.some((item) => String(item.id) === String(place.attractionId))) {
      list.push({
        id: place.attractionId,
        name: `景点 #${place.attractionId}`,
        city: '',
        status: 'DISABLED',
        longitude: null,
        latitude: null
      })
    }
  }
  return list
})

function attractionLabel(attraction) {
  if (!hasCoordinates(attraction) || attraction.status !== 'ACTIVE') {
    return `${attraction.name}（${attraction.city || '—'}）· 已停用或缺坐标，请替换`
  }
  return `${attraction.name}（${attraction.city || '—'}）`
}

function reset() {
  const source = props.guide || {}
  Object.assign(form, {
    title: source.title || '',
    summary: source.summary || '',
    city: source.city || '',
    destination: source.destination || '',
    coverUrl: source.coverUrl || ''
  })
  const sourcePlaces = Array.isArray(source.places) ? source.places : []
  places.value = sourcePlaces.length
    ? sourcePlaces.map((place) => ({ attractionId: place.attractionId, note: place.note || '' }))
    : [{ attractionId: '', note: '' }, { attractionId: '', note: '' }]
  formError.value = ''
  loadOptions()
}

watch(() => [props.modelValue, props.guide], () => {
  if (props.modelValue) reset()
}, { immediate: true })

function close() {
  emit('update:modelValue', false)
}

/** 空串与纯空白一律按"未填写"处理，提交 null 而不是空字符串。 */
function optional(value) {
  const text = String(value ?? '').trim()
  return text === '' ? null : text
}

function addPlace() {
  if (places.value.length >= MAX_PLACES) return
  places.value.push({ attractionId: '', note: '' })
}

function removePlace(index) {
  if (places.value.length <= MIN_PLACES) return
  places.value.splice(index, 1)
}

function movePlace(index, delta) {
  const target = index + delta
  if (target < 0 || target >= places.value.length) return
  const [moved] = places.value.splice(index, 1)
  places.value.splice(target, 0, moved)
}

/** 封面地址按契约 format: uri 校验；未填写返回 null。 */
function coverError(value) {
  const text = String(value ?? '').trim()
  if (text === '') return ''
  if (codePointLength(text) > COVER_MAX) return `封面地址最多 ${COVER_MAX} 个字符`
  try {
    const url = new URL(text)
    if (url.protocol !== 'http:' && url.protocol !== 'https:') return '封面地址需为 http(s) 链接'
  } catch {
    return '封面地址需为合法的 http(s) 链接'
  }
  return ''
}

function validate() {
  const title = form.title.trim()
  const city = form.city.trim()
  if (codePointLength(title) < TITLE_MIN) return `指南标题至少 ${TITLE_MIN} 个字符`
  if (codePointLength(title) > TITLE_MAX) return `指南标题最多 ${TITLE_MAX} 个字符`
  if (codePointLength(form.summary) > SUMMARY_MAX) return `指南摘要最多 ${SUMMARY_MAX} 个字符`
  if (!city) return '请填写所属城市'
  if (codePointLength(city) > CITY_MAX) return `所属城市最多 ${CITY_MAX} 个字符`
  if (codePointLength(form.destination) > DESTINATION_MAX) return `目的地最多 ${DESTINATION_MAX} 个字符`
  const coverInvalid = coverError(form.coverUrl)
  if (coverInvalid) return coverInvalid

  const chosen = places.value.map((place) => String(place.attractionId ?? '').trim())
  if (chosen.some((id) => id === '')) return '每个地点都要选择景点'
  if (new Set(chosen).size !== chosen.length) return '地点不能重复'
  if (places.value.length < MIN_PLACES || places.value.length > MAX_PLACES) {
    return `指南需要 ${MIN_PLACES} 到 ${MAX_PLACES} 个地点`
  }
  // 候选景点还没取回来时无法判断地点是否"启用且有坐标"。此时若直接判定"地点不合法"，
  // 会让运营去替换一个其实正确的选择，因此先要求等待取数完成。
  if (optionsLoading.value) {
    return '正在加载候选景点，请稍候再保存'
  }
  // 候选为空且取数失败过：真正的原因是"没取到数据"，不是"地点不合法"，照实说明。
  if (!attractionOptions.value.length && optionsError.value) {
    return optionsError.value
  }
  const invalid = places.value.find((place) =>
    !attractionOptions.value.some((item) => String(item.id) === String(place.attractionId)))
  if (invalid) {
    return '指南地点必须是带经纬度的已启用景点，请替换停用或缺坐标的地点'
  }
  if (places.value.some((place) => codePointLength(place.note) > NOTE_MAX)) {
    return `地点备注最多 ${NOTE_MAX} 个字符`
  }
  return ''
}

async function save() {
  if (submitting.value) return
  formError.value = ''
  const invalid = validate()
  if (invalid) return ElMessage.warning(invalid)

  const payload = {
    title: form.title.trim(),
    summary: optional(form.summary),
    city: form.city.trim(),
    destination: optional(form.destination),
    coverUrl: optional(form.coverUrl),
    places: places.value.map((place) => ({
      attractionId: String(place.attractionId),
      note: optional(place.note)
    }))
  }

  submitting.value = true
  try {
    const saved = editing.value
      ? await adminApi.updatePlaceGuide(props.guide.id, payload)
      : await adminApi.createPlaceGuide(payload)
    ElMessage.success(editing.value ? '地点指南已更新' : '地点指南草稿已创建')
    emit('saved', { guide: saved, created: !editing.value })
    close()
  } catch (cause) {
    // 422（地点不合法 / 字段语义）、403、404 的 message 都可读，就地展示，不关闭弹窗、不丢输入。
    formError.value = cause.message || '地点指南保存失败，请稍后重试'
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-dialog
    append-to-body
    class="place-guide-form-dialog"
    :model-value="modelValue"
    :title="editing ? '编辑地点指南' : '新增地点指南'"
    width="min(760px, calc(100vw - 32px))"
    :close-on-click-modal="!submitting"
    :show-close="!submitting"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div class="dialog-form-grid">
      <p v-if="formError" class="form-error wide" role="alert">{{ formError }}</p>

      <div class="form-field wide">
        <label>指南标题 <span class="req">*</span></label>
        <input v-model="form.title" placeholder="例如：杭州双景点地图指南" />
        <p class="form-counter" :class="{ over: codePointLength(form.title) > TITLE_MAX }">
          {{ codePointLength(form.title) }} / {{ TITLE_MAX }}（至少 {{ TITLE_MIN }}）
        </p>
      </div>

      <div class="form-field">
        <label>所属城市 <span class="req">*</span></label>
        <input v-model="form.city" placeholder="例如：杭州" />
        <p class="form-counter" :class="{ over: codePointLength(form.city) > CITY_MAX }">
          {{ codePointLength(form.city) }} / {{ CITY_MAX }}
        </p>
      </div>

      <div class="form-field">
        <label>目的地</label>
        <input v-model="form.destination" placeholder="例如：杭州" />
        <p class="form-counter" :class="{ over: codePointLength(form.destination) > DESTINATION_MAX }">
          {{ codePointLength(form.destination) }} / {{ DESTINATION_MAX }}
        </p>
      </div>

      <div class="form-field wide">
        <label>指南摘要</label>
        <textarea v-model="form.summary" rows="2" placeholder="一句话说明这份指南涵盖哪些地点"></textarea>
        <p class="form-counter" :class="{ over: codePointLength(form.summary) > SUMMARY_MAX }">
          {{ codePointLength(form.summary) }} / {{ SUMMARY_MAX }}
        </p>
      </div>

      <div class="form-field wide">
        <label>封面地址</label>
        <input v-model="form.coverUrl" placeholder="https://…（可留空）" />
        <p class="form-counter" :class="{ over: codePointLength(form.coverUrl) > COVER_MAX }">
          {{ codePointLength(form.coverUrl) }} / {{ COVER_MAX }}
        </p>
      </div>

      <div class="place-editor wide">
        <div class="place-head">
          <label>地点清单 <span class="req">*</span>（{{ places.length }} / {{ MAX_PLACES }}，至少 {{ MIN_PLACES }} 个）</label>
          <button
            type="button"
            class="secondary-button small"
            :disabled="places.length >= MAX_PLACES"
            @click="addPlace"
          >
            + 添加地点
          </button>
        </div>
        <p class="form-hint">
          地点顺序即用户端地图的展示顺序。只能选择已启用且带经纬度的景点，所选景点将决定指南地图的聚焦区域。
          <span v-if="optionsLoading">正在加载景点候选…</span>
          <span v-else-if="optionsError" class="hint-error" role="alert">{{ optionsError }}</span>
        </p>

        <div v-for="(place, index) in places" :key="place.attractionId || `row-${index}`" class="place-row">
          <span class="place-order">{{ index + 1 }}</span>
          <select v-model="place.attractionId" class="place-select">
            <option value="">请选择景点</option>
            <option
              v-for="attraction in selectableAttractions"
              :key="attraction.id"
              :value="attraction.id"
              :disabled="!isSelectable(attraction.id, index)"
            >
              {{ attractionLabel(attraction) }}
            </option>
          </select>
          <input v-model="place.note" class="place-note" placeholder="地点备注（可留空）" />
          <div class="place-actions">
            <button type="button" class="text-button" :disabled="index === 0" @click="movePlace(index, -1)">上移</button>
            <button
              type="button"
              class="text-button"
              :disabled="index === places.length - 1"
              @click="movePlace(index, 1)"
            >
              下移
            </button>
            <button
              type="button"
              class="text-button text-danger"
              :disabled="places.length <= MIN_PLACES"
              @click="removePlace(index)"
            >
              删除
            </button>
          </div>
        </div>
      </div>

      <p class="form-hint wide">
        新建的指南为草稿，确认地点无误后请在列表里「发布」。发布后才会出现在用户端指南页。
      </p>
    </div>

    <template #footer>
      <button class="secondary-button" :disabled="submitting" @click="close">取消</button>
      <button class="primary-button" :disabled="submitting" @click="save">
        {{ submitting ? '保存中…' : '保存指南' }}
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

.hint-error {
  color: var(--danger-red);
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

.place-editor {
  border: 1px solid var(--border-divider);
  border-radius: 10px;
  padding: 12px;
  background: var(--bg-subtle, #fafafa);
}

.place-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.secondary-button.small {
  min-height: 28px;
  padding: 0 10px;
  font-size: 12px;
}

.place-row {
  display: grid;
  grid-template-columns: 24px minmax(0, 1.4fr) minmax(0, 1fr) auto;
  align-items: center;
  gap: 8px;
  margin-top: 10px;
}

.place-order {
  display: grid;
  place-items: center;
  width: 22px;
  height: 22px;
  border-radius: 50%;
  background: var(--brand-primary, #0071e3);
  color: #fff;
  font-size: 12px;
  font-weight: 700;
}

.place-select,
.place-note {
  width: 100%;
  padding: 7px 10px;
  border: 1px solid var(--border-strong);
  border-radius: 8px;
  background: #fff;
  font-size: 13px;
  color: var(--text-primary);
}

.place-actions {
  white-space: nowrap;
}

.text-danger {
  color: var(--danger-red) !important;
}

@media (max-width: 720px) {
  .dialog-form-grid {
    grid-template-columns: 1fr;
  }

  .place-row {
    grid-template-columns: 24px 1fr;
  }

  .place-note,
  .place-actions {
    grid-column: 2 / -1;
  }
}
</style>
