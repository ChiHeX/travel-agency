<script setup>
import { reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { adminApi } from '@/api/modules'

/**
 * 酒店资料新增 / 修改表单弹窗（对应契约 POST /admin/hotels 与 PUT /admin/hotels/{hotelId}）。
 *
 * 只提交契约 HotelUpsertRequest 允许的字段：`id`、`createdAt`、`updatedAt` 都不在前端提交范围内
 * —— 主键由后端生成，审计时间由数据库维护。提交这些字段会被后端严格模式直接拒绝（400）。
 *
 * 字段口径与后端一致：
 * - `status` 是契约 AccountStatus 枚举 `ACTIVE` / `DISABLED`（后端映射成库内 1/0），
 *   新建默认 ACTIVE；DISABLED 表示停用该资料，但不影响已经被线路行程引用的行程内容，
 *   只是不能再被安排进新的每日行程（后端会拒绝，行程编辑的下拉里也标注为「已停用」）；
 * - `longitude` / `latitude` 是 JSON number，且必须在经度 ±180、纬度 ±90 之内（后端 422 兜底）；
 * - `address` / `contactPhone` / `intro` / 坐标允许为空，提交 null 表示清空（PUT 会真的写 NULL）。
 *
 * 酒店在 PRD 里只作为线路行程资源存在（PRD §10、§36）：表单不含房型、库存与价格字段，
 * 本项目不提供酒店订单与单独下单。
 *
 * 页面校验只用于改善交互，最终由后端裁定；后端返回的 message 会就地展示。
 *
 * 保存成功后 emit `saved`，载荷是 `{ hotel, created }`：`hotel` 是后端返回的酒店，
 * `created` 表示这次走的是 POST（新建）还是 PUT（修改），由调用方决定刷新哪一页。
 */
const props = defineProps({
  modelValue: { type: Boolean, default: false },
  hotel: { type: Object, default: null }
})
const emit = defineEmits(['update:modelValue', 'saved'])

const submitting = ref(false)
const formError = ref('')

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
  const source = props.hotel || {}
  Object.assign(form, {
    name: source.name || '',
    address: source.address || '',
    contactPhone: source.contactPhone || '',
    // 坐标按契约是 number：null 时留空，由用户决定是否填写。
    longitude: source.longitude === null || source.longitude === undefined ? '' : String(source.longitude),
    latitude: source.latitude === null || source.latitude === undefined ? '' : String(source.latitude),
    intro: source.intro || '',
    dataSource: source.dataSource || '',
    status: source.status === 'DISABLED' ? 'DISABLED' : 'ACTIVE'
  })
  formError.value = ''
}

watch(() => [props.modelValue, props.hotel], () => {
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

/**
 * 按 Unicode 码点计数。
 *
 * 契约的 `maxLength` 是 JSON Schema 口径，数的是字符（码点），后端的
 * `@CodePointLength` 也是这个口径；而 JS 的 `String#length` 数的是 UTF-16 码元，
 * 一个 emoji 会被算成 2。用 `.length` 做校验会把契约允许的内容误判成超长
 * （例如 100 个 emoji 的酒店名：码点 100 ≤ 128 合法，码元却是 200）。
 * 输入框上的 maxlength 属性只是打字时的便利用户体验，最终以上面的口径为准。
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
 * 页面侧校验：与后端 HotelUpsertRequest 的约束保持一致
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

async function save() {
  if (submitting.value) return
  formError.value = ''
  const invalid = validate()
  if (invalid) return ElMessage.warning(invalid)

  const payload = {
    name: form.name.trim(),
    address: optional(form.address),
    contactPhone: optional(form.contactPhone),
    longitude: coordinate(form.longitude, -180, 180, '经度').value,
    latitude: coordinate(form.latitude, -90, 90, '纬度').value,
    intro: optional(form.intro),
    dataSource: form.dataSource.trim(),
    status: form.status
  }

  submitting.value = true
  const editing = Boolean(props.hotel?.id)
  try {
    const saved = editing
      ? await adminApi.updateHotel(props.hotel.id, payload)
      : await adminApi.createHotel(payload)
    ElMessage.success(editing ? '酒店资料已更新' : '酒店资料已新增')
    // 带上 created：POST 和 PUT 对列表的影响不同 —— 新建的记录排在第一页，
    // 修改的记录留在原来的位置（created_at 不变），页面据此决定刷新哪一页。
    emit('saved', { hotel: saved, created: !editing })
    close()
  } catch (cause) {
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

      <div class="form-field wide">
        <label>酒店名称 <span class="req">*</span></label>
        <input v-model="form.name" maxlength="128" placeholder="例如：大理古城演示酒店" />
      </div>

      <div class="form-field">
        <label>联系电话</label>
        <input v-model="form.contactPhone" maxlength="20" placeholder="例如：0872-1234567" />
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
        <input v-model="form.address" maxlength="255" placeholder="例如：云南省大理白族自治州大理市" />
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
        <input v-model="form.dataSource" maxlength="500" placeholder="例如：团队整理的测试数据；坐标仅用于软件演示" />
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
