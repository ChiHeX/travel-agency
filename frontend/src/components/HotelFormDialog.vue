<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { adminApi } from '@/api/modules'

/**
 * 酒店资料新增 / 修改表单弹窗（对应契约 POST /admin/hotels 与 PUT /admin/hotels/{hotelId}）。
 *
 * 只提交契约字段：新增走 `HotelCreateRequest`（**不含** `version`，版本由服务端从 0 起算），
 * 修改走 `HotelUpdateRequest`（**必填** `version`，回传读取时拿到的版本号）；
 * `id`、`createdAt`、`updatedAt` 都不在前端提交范围内 —— 主键由后端生成，审计时间由数据库维护，
 * 提交这些字段会被后端严格模式直接拒绝（400）。
 *
 * 字段口径与后端一致：
 * - `status` 是契约 AccountStatus 枚举 `ACTIVE` / `DISABLED`（后端映射成库内 1/0），
 *   新建默认 ACTIVE；DISABLED 表示停用该资料，但不影响已经被线路行程引用的行程内容，
 *   只是不能再被安排进新的每日行程（后端会拒绝，行程编辑的下拉里也标注为「已停用」）；
 *   **编辑时只有用户真的改过状态才提交该字段**，否则后端会保留库内现值，避免用旧状态
 *   覆盖另一位管理员的并发停用（见 `shouldSubmitStatus`）；
 * - `longitude` / `latitude` 是 JSON number，且必须在经度 ±180、纬度 ±90 之内（后端 422 兜底）；
 * - `address` / `contactPhone` / `intro` / 坐标允许为空，提交 null 表示清空（PUT 会真的写 NULL）。
 *
 * 酒店在 PRD 里只作为线路行程资源存在（PRD §10、§36）：表单不含房型、库存与价格字段，
 * 本项目不提供酒店订单与单独下单。
 *
 * 页面校验只用于改善交互，最终由后端裁定；后端返回的 message 会就地展示。
 *
 * **版本冲突**（409 `HOTEL_VERSION_CONFLICT`）：另一位工作人员在本次编辑期间改过这份资料。
 * 表单不会丢弃用户填写的内容，而是就地展示冲突面板：拉取服务器最新资料、列出有差异的字段，
 * 由用户选择「载入服务器最新数据」或「保留我的修改并覆盖」（用服务器最新版本号重新提交）。
 *
 * 保存成功后 emit `saved`，载荷是 `{ hotel, created }`：`hotel` 是后端返回的酒店（含最新 `version`），
 * `created` 表示这次走的是 POST（新建）还是 PUT（修改），由调用方决定刷新哪一页。
 */
const props = defineProps({
  modelValue: { type: Boolean, default: false },
  hotel: { type: Object, default: null }
})
const emit = defineEmits(['update:modelValue', 'saved'])

const submitting = ref(false)
const formError = ref('')

/**
 * 打开弹窗时表单里回填的状态（新建时为契约默认的 ACTIVE）。
 * 编辑时用它判断用户是否真的改过状态 —— 只有改过才提交 `status`，见 `shouldSubmitStatus()`。
 */
const originalStatus = ref('ACTIVE')

/**
 * 本次修改要提交的乐观锁版本号：以服务端确认的状态为准。
 * - 打开弹窗时取 `props.hotel.version`；
 * - 保存成功后更新为响应里的新版本（否则第二次保存会拿着过期版本必然冲突）；
 * - 冲突面板里"覆盖"时更新为服务器最新版本。
 * 新建时为 `null`，请求体不带该字段。
 */
const baseVersion = ref(null)

/** 冲突面板状态：服务端最新资料、加载中、以及是否已经取到。 */
const conflictMessage = ref('')
const conflictLatest = ref(null)
const conflictLoading = ref(false)

const EDITABLE_FIELD_LABELS = {
  name: '酒店名称',
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
  address: '',
  contactPhone: '',
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

/** 空串与纯空白一律按"未填写"处理，提交 null 而不是空字符串。 */
function optional(value) {
  const text = String(value ?? '').trim()
  return text === '' ? null : text
}

/**
 * 按 Unicode 码点计数。
 *
 * 契约的 `maxLength` 是 JSON Schema 口径，数的是字符（码点），后端的
 * `@CodePointLength` 也是这个口径；而 JS 的 `String#length` 数的是 UTF-16 码元，
 * 一个 emoji 会被算成 2。用 `.length` 做校验会把契约允许的内容误判成超长
 * （例如 100 个 emoji 的酒店名：码点 100 ≤ 128 合法，码元却是 200）。
 * 输入框因此<b>不设 maxlength</b>：HTML 的 maxlength 同样按 UTF-16 码元截断，
 * 会让这类内容在输入阶段就被静默截掉，最终以上面的口径为准。
 */
const codePointLength = (value) => [...String(value ?? '')].length

/** 坐标必须是契约允许范围内的数字；未填写返回 null。 */
function coordinate(value, min, max, label) {
  const text = String(value ?? '').trim()
  if (text === '') return { value: null }
  const number = Number(text)
  if (!Number.isFinite(number)) return { error: `${label}应为数字` }
  if (number < min || number > max) return { error: `${label}应在 ${min} 到 ${max} 之间` }
  return { value: number }
}

/**
 * 页面侧校验：与后端 HotelCreateRequest / HotelUpdateRequest 的约束保持一致
 * （必填、长度上限、坐标范围），让运营在提交前就看到问题，而不是等接口回一个 422。
 */
function validate() {
  const name = form.name.trim()
  const dataSource = form.dataSource.trim()
  if (!name) return '请填写酒店名称'
  if (codePointLength(name) > 128) return '酒店名称最多 128 个字符'
  if (codePointLength(form.address.trim()) > 255) return '酒店地址最多 255 个字符'
  if (codePointLength(form.contactPhone.trim()) > 20) return '联系电话最多 20 个字符'
  if (codePointLength(form.intro) > 10000) return '酒店简介最多 10000 个字符'
  if (!dataSource) return '请填写数据来源说明'
  if (codePointLength(dataSource) > 500) return '数据来源说明最多 500 个字符'
  const longitude = coordinate(form.longitude, -180, 180, '经度')
  if (longitude.error) return longitude.error
  const latitude = coordinate(form.latitude, -90, 90, '纬度')
  if (latitude.error) return latitude.error
  return ''
}

/**
 * 这次保存是否要把 `status` 一起提交。
 *
 * 编辑时**只在用户真的改了状态之后**才提交它。后端的口径是：契约里 `status` 不是必填，
 * 未提交表示"保持库内现值"，只有显式提交才写这一列 —— 每次编辑都顺手带上表单里的旧状态
 * 会让这个保护完全失效：管理员只改地址时，用的是打开弹窗那一刻读到的旧 `ACTIVE`，
 * 而另一位管理员可能刚好在这期间把这家酒店停用了，于是这次"只改地址"的保存
 * 会把它重新启用（读旧值 → 对方停用并提交 → 本事务把 ACTIVE 写回）。
 *
 * 新建没有"库内现值"可言，状态是本次建档的明确意图，一律提交。
 */
function shouldSubmitStatus() {
  if (!props.hotel?.id) return true
  return form.status !== originalStatus.value
}

function close() {
  emit('update:modelValue', false)
}

/** 冲突面板要展示的差异字段（只列真正不一样的部分，避免整屏都是"服务器 vs 你填写"）。 */
const conflictDifferences = computed(() => {
  const latest = conflictLatest.value
  if (!latest) return []
  const server = {
    name: latest.name || '',
    address: latest.address || '',
    contactPhone: latest.contactPhone || '',
    status: latest.status === 'DISABLED' ? 'DISABLED' : 'ACTIVE',
    longitude: latest.longitude === null || latest.longitude === undefined ? '' : String(latest.longitude),
    latitude: latest.latitude === null || latest.latitude === undefined ? '' : String(latest.latitude),
    intro: latest.intro || '',
    dataSource: latest.dataSource || ''
  }
  const mine = {
    name: form.name.trim(),
    address: form.address.trim(),
    contactPhone: form.contactPhone.trim(),
    status: form.status,
    longitude: String(form.longitude ?? '').trim(),
    latitude: String(form.latitude ?? '').trim(),
    intro: form.intro,
    dataSource: form.dataSource.trim()
  }
  return Object.keys(EDITABLE_FIELD_LABELS).filter((key) => server[key] !== mine[key])
})

const differs = (key) => conflictDifferences.value.includes(key)

/**
 * 取回服务器最新资料：走契约的 `GET /admin/hotels/{hotelId}`，**按主键**读取。
 *
 * <p>不用列表端点按名称检索：另一位管理员可能已经改过名称（旧名称检索不到），
 * 同名资料也可能超过一页 —— 那样接口正确报了冲突，用户却「载入最新数据」和
 * 「保留我的修改并覆盖」都做不了，冲突提示等于没有出口。
 * 取不到时（例如资料已被删除）面板会提示重新打开表单，不会假装已同步。</p>
 */
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

/** 采用服务器最新数据：表单回到服务器状态，之后的保存以最新版本号为基准。 */
function adoptLatest() {
  if (!conflictLatest.value) return
  const latest = conflictLatest.value
  applyHotel(latest)
  conflictMessage.value = ''
  conflictLatest.value = null
  ElMessage.success('已载入服务器最新资料，请确认后再保存')
}

/**
 * 保留我的修改并覆盖：把基准版本换成服务器最新版本号后重新提交。
 *
 * <p>这是用户明确选择的覆盖动作 —— 版本号只负责发现"基于过期数据提交"，
 * 不阻止用户在知情后覆盖；本次覆盖同样会记入操作日志。</p>
 */
async function overwriteLatest() {
  if (!conflictLatest.value) return
  baseVersion.value = conflictLatest.value.version ?? baseVersion.value
  conflictMessage.value = ''
  conflictLatest.value = null
  await save()
}

/** 用一份酒店数据回填表单（打开弹窗与"载入服务器最新数据"共用）。 */
function applyHotel(source) {
  const status = source?.status === 'DISABLED' ? 'DISABLED' : 'ACTIVE'
  Object.assign(form, {
    name: source?.name || '',
    address: source?.address || '',
    contactPhone: source?.contactPhone || '',
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
    address: optional(form.address),
    contactPhone: optional(form.contactPhone),
    longitude: coordinate(form.longitude, -180, 180, '经度').value,
    latitude: coordinate(form.latitude, -90, 90, '纬度').value,
    intro: optional(form.intro),
    dataSource: form.dataSource.trim(),
    // 未改状态时不带这个字段，交给后端保留库内现值（并发停用不会被覆盖）。
    ...(shouldSubmitStatus() ? { status: form.status } : {}),
    // 修改必填 version（契约 HotelUpdateRequest 的乐观锁）；新建不带（版本由服务端从 0 起算）。
    ...(editing ? { version: baseVersion.value } : {})
  }

  submitting.value = true
  try {
    const saved = editing
      ? await adminApi.updateHotel(props.hotel.id, payload)
      : await adminApi.createHotel(payload)
    // 保存成功后，"原始值"与基准版本都要以服务端确认的结果为准：否则"先停用保存、再改回启用保存"
    // 的第二次保存会因为值等于打开弹窗时的旧值而漏掉 status，第二次提交也会拿着过期版本必冲突。
    if (saved?.status === 'ACTIVE' || saved?.status === 'DISABLED') {
      originalStatus.value = saved.status
    }
    if (saved?.version !== null && saved?.version !== undefined) {
      baseVersion.value = saved.version
    }
    ElMessage.success(editing ? '酒店资料已更新' : '酒店资料已新增')
    // 带上 created：POST 和 PUT 对列表的影响不同 —— 新建的记录排在第一页，
    // 修改的记录留在原来的位置（created_at 不变），页面据此决定刷新哪一页。
    emit('saved', { hotel: saved, created: !editing })
    close()
  } catch (cause) {
    if (editing && cause.status === 409 && cause.code === 'HOTEL_VERSION_CONFLICT') {
      // 另一位工作人员在本次编辑期间改过这份资料：不丢弃用户输入，交给冲突面板处理。
      conflictMessage.value = cause.message || '这份酒店资料已被他人修改'
      conflictLatest.value = null
      await loadLatest()
      return
    }
    // 后端 422（字段语义）与 404（记录已被删除）的 message 都可读，就地展示。
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

      <!--
        版本冲突（409 HOTEL_VERSION_CONFLICT）：另一位工作人员在本次编辑期间改过这份资料。
        不丢弃用户已经填写的内容：先列出服务器最新值与差异字段，再由用户决定采用哪一份。
      -->
      <div v-if="conflictMessage" class="conflict-panel wide" role="alert">
        <p class="conflict-title">{{ conflictMessage }}</p>
        <p class="conflict-note">你填写的内容已保留，没有被丢弃。</p>
        <el-skeleton v-if="conflictLoading" :rows="3" animated />
        <template v-else-if="conflictLatest">
          <p v-if="conflictDifferences.length" class="conflict-note conflict-diff">
            与服务器不一致的字段：
            <strong>{{ conflictDifferences.map((key) => EDITABLE_FIELD_LABELS[key]).join('、') }}</strong>；
            服务器当前版本 {{ conflictLatest.version }}。
          </p>
          <p v-else class="conflict-note conflict-diff">你填写的各项与服务器当前值一致。</p>
          <dl class="conflict-grid">
            <div :class="{ 'conflict-row-differs': differs('name') }">
              <dt>酒店名称</dt>
              <dd>
                <span class="conflict-server">服务器：{{ conflictLatest.name }}</span>
                <span class="conflict-mine">你填写：{{ form.name.trim() || '（空）' }}</span>
              </dd>
            </div>
            <div :class="{ 'conflict-row-differs': differs('status') }">
              <dt>运营状态</dt>
              <dd>
                <span class="conflict-server">服务器：{{ conflictLatest.status === 'DISABLED' ? '停用' : '启用' }}</span>
                <span class="conflict-mine">你填写：{{ form.status === 'DISABLED' ? '停用' : '启用' }}</span>
              </dd>
            </div>
            <div :class="{ 'conflict-row-differs': differs('address') }">
              <dt>详细地址</dt>
              <dd>
                <span class="conflict-server">服务器：{{ conflictLatest.address || '（空）' }}</span>
                <span class="conflict-mine">你填写：{{ form.address.trim() || '（空）' }}</span>
              </dd>
            </div>
            <div :class="{ 'conflict-row-differs': differs('contactPhone') }">
              <dt>联系电话</dt>
              <dd>
                <span class="conflict-server">服务器：{{ conflictLatest.contactPhone || '（空）' }}</span>
                <span class="conflict-mine">你填写：{{ form.contactPhone.trim() || '（空）' }}</span>
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
        <!-- 不设 maxlength：HTML 数的是 UTF-16 码元，会把契约允许的 emoji 名称静默截断 -->
        <input v-model="form.name" placeholder="例如：大理古城演示酒店" />
      </div>

      <div class="form-field">
        <label>联系电话</label>
        <input v-model="form.contactPhone" placeholder="例如：0872-1234567" />
      </div>

      <div class="form-field">
        <label>运营状态</label>
        <select v-model="form.status">
          <option value="ACTIVE">启用（可用于行程）</option>
          <option value="DISABLED">停用（不再安排进新行程）</option>
        </select>
      </div>

      <div class="form-field wide">
        <label>详细地址</label>
        <input v-model="form.address" placeholder="例如：云南省大理白族自治州大理市" />
      </div>

      <div class="form-field">
        <label>经度</label>
        <input v-model="form.longitude" inputmode="decimal" placeholder="例如：100.1650000" />
      </div>

      <div class="form-field">
        <label>纬度</label>
        <input v-model="form.latitude" inputmode="decimal" placeholder="例如：25.6940000" />
      </div>

      <div class="form-field wide">
        <label>酒店简介</label>
        <textarea v-model="form.intro" rows="3" placeholder="用于线路行程展示的酒店介绍文字"></textarea>
      </div>

      <div class="form-field wide">
        <label>数据来源说明 <span class="req">*</span></label>
        <input v-model="form.dataSource" placeholder="例如：团队整理的测试数据；坐标仅用于软件演示" />
        <p class="form-hint">
          资料必须可追溯：来源说明会随酒店一起保存，供后台核对与展示，不得留空。
        </p>
      </div>

      <p class="form-hint wide">
        经纬度用于每日行程的地图标注，请填写 WGS-84 坐标；未填写坐标的酒店仍可保存并安排进行程。
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

.form-hint {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--text-secondary);
}

/* 版本冲突面板：与团期表单同一套视觉口径，避免同一个系统里出现两种冲突提示。 */
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
