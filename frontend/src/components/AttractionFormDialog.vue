<script setup>
import { reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { adminApi } from '@/api/modules'

/**
 * 景点新增 / 修改表单弹窗（对应契约 POST /admin/attractions 与 PUT /admin/attractions/{attractionId}）。
 *
 * 只提交契约 AttractionUpsertRequest 允许的字段：`id`、`createdAt`、`updatedAt` 都不在前端提交范围内
 * —— 主键由后端生成，审计时间由数据库维护。提交这些字段会被后端严格模式直接拒绝（400）。
 *
 * 字段口径与后端一致：
 * - `status` 是契约 AccountStatus 枚举 `ACTIVE` / `DISABLED`（后端映射成库内 1/0），
 *   新建默认 ACTIVE；DISABLED 表示从对外页面撤下（公开列表与详情都不再返回该景点）；
 * - `longitude` / `latitude` 是 JSON number，且必须在经度 ±180、纬度 ±90 之内（后端 422 兜底）；
 * - `address` / `intro` / 坐标允许为空，提交 null 表示清空（PUT 会真的写 NULL）。
 *
 * 页面校验只用于改善交互，最终由后端裁定；后端返回的 message 会就地展示。
 */
const props = defineProps({
  modelValue: { type: Boolean, default: false },
  attraction: { type: Object, default: null }
})
const emit = defineEmits(['update:modelValue', 'saved'])

const submitting = ref(false)
const formError = ref('')

const form = reactive({
  name: '',
  city: '',
  address: '',
  longitude: '',
  latitude: '',
  intro: '',
  dataSource: '',
  status: 'ACTIVE'
})

function reset() {
  const source = props.attraction || {}
  Object.assign(form, {
    name: source.name || '',
    city: source.city || '',
    address: source.address || '',
    // 坐标按契约是 number：null 时留空，由用户决定是否填写。
    longitude: source.longitude === null || source.longitude === undefined ? '' : String(source.longitude),
    latitude: source.latitude === null || source.latitude === undefined ? '' : String(source.latitude),
    intro: source.intro || '',
    dataSource: source.dataSource || '',
    status: source.status === 'DISABLED' ? 'DISABLED' : 'ACTIVE'
  })
  formError.value = ''
}

watch(() => [props.modelValue, props.attraction], () => {
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
 * 页面侧校验：与后端 AttractionUpsertRequest 的约束保持一致
 * （必填、长度上限、坐标范围），让运营在提交前就看到问题，而不是等接口回一个 422。
 */
function validate() {
  const name = form.name.trim()
  const city = form.city.trim()
  const dataSource = form.dataSource.trim()
  if (!name) return '请填写景点名称'
  if (name.length > 128) return '景点名称最多 128 个字符'
  if (!city) return '请填写所属城市'
  if (city.length > 64) return '所属城市最多 64 个字符'
  if (form.address.trim().length > 255) return '景点地址最多 255 个字符'
  if (form.intro.length > 10000) return '景点简介最多 10000 个字符'
  if (!dataSource) return '请填写数据来源说明'
  if (dataSource.length > 500) return '数据来源说明最多 500 个字符'
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
    city: form.city.trim(),
    address: optional(form.address),
    longitude: coordinate(form.longitude, -180, 180, '经度').value,
    latitude: coordinate(form.latitude, -90, 90, '纬度').value,
    intro: optional(form.intro),
    dataSource: form.dataSource.trim(),
    status: form.status
  }

  submitting.value = true
  const editing = Boolean(props.attraction?.id)
  try {
    const saved = editing
      ? await adminApi.updateAttraction(props.attraction.id, payload)
      : await adminApi.createAttraction(payload)
    ElMessage.success(editing ? '景点资料已更新' : '景点资料已新增')
    emit('saved', saved)
    close()
  } catch (cause) {
    // 后端 422（字段语义）与 404（记录已被删除）的 message 都可读，就地展示。
    formError.value = cause.message || '景点资料保存失败，请稍后重试'
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-dialog
    append-to-body
    class="attraction-form-dialog"
    :model-value="modelValue"
    :title="attraction?.id ? '编辑景点资料' : '新增景点资料'"
    width="min(680px, calc(100vw - 32px))"
    :close-on-click-modal="!submitting"
    :show-close="!submitting"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div class="dialog-form-grid">
      <p v-if="formError" class="form-error wide" role="alert">{{ formError }}</p>

      <div class="form-field wide">
        <label>景点名称 <span class="req">*</span></label>
        <input v-model="form.name" maxlength="128" placeholder="例如：大理古城" />
      </div>

      <div class="form-field">
        <label>所属城市 <span class="req">*</span></label>
        <input v-model="form.city" maxlength="64" placeholder="例如：大理" />
      </div>

      <div class="form-field">
        <label>运营状态</label>
        <select v-model="form.status">
          <option value="ACTIVE">启用（对用户可见）</option>
          <option value="DISABLED">停用（从用户端撤下）</option>
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
        <label>景点简介</label>
        <textarea v-model="form.intro" rows="3" placeholder="用于用户端景点详情的介绍文字"></textarea>
      </div>

      <div class="form-field wide">
        <label>数据来源说明 <span class="req">*</span></label>
        <input v-model="form.dataSource" maxlength="500" placeholder="例如：团队整理的测试数据；坐标仅用于软件演示" />
        <p class="form-hint">
          资料必须可追溯：来源说明会随景点一起保存，供后台核对与展示，不得留空。
        </p>
      </div>

      <p class="form-hint wide">
        经纬度用于用户端地图定位与行程连线，请填写 WGS-84 坐标；未填写坐标的景点仍可保存，
        但不会出现在地图上。停用只是从用户端撤下，不会删除资料，也不会影响已引用它的线路行程。
      </p>
    </div>

    <template #footer>
      <button class="secondary-button" :disabled="submitting" @click="close">取消</button>
      <button class="primary-button" :disabled="submitting" @click="save">
        {{ submitting ? '保存中…' : '保存景点' }}
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
  .attraction-form-dialog { margin-top: 5dvh; }
  .attraction-form-dialog .el-dialog__body { max-height: 65dvh; overflow-y: auto; }
}
</style>
