<script setup>
import { reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { adminApi } from '@/api/modules'

/**
 * 酒店资料表单弹窗（新增 / 修改共用）。
 * 字段严格遵循契约 HotelUpsertRequest。
 */
const props = defineProps({
  modelValue: { type: Boolean, default: false },
  hotel: { type: Object, default: null }
})
const emit = defineEmits(['update:modelValue', 'saved'])

const submitting = ref(false)
const submitError = ref('')

const form = reactive({
  name: '',
  dataSource: '',
  address: '',
  contactPhone: '',
  longitude: '',
  latitude: '',
  intro: '',
  status: 'ACTIVE'
})

function reset() {
  const source = props.hotel || {}
  Object.assign(form, {
    name: source.name || '',
    dataSource: source.dataSource || '',
    address: source.address || '',
    contactPhone: source.contactPhone || '',
    longitude: source.longitude == null ? '' : source.longitude,
    latitude: source.latitude == null ? '' : source.latitude,
    intro: source.intro || '',
    status: source.status || 'ACTIVE'
  })
  submitError.value = ''
}

watch(
  () => [props.modelValue, props.hotel],
  () => {
    if (props.modelValue) reset()
  },
  { immediate: true }
)

function close() {
  emit('update:modelValue', false)
}

function optional(value) {
  const trimmed = (value || '').toString().trim()
  return trimmed === '' ? null : trimmed
}

function coordinate(value) {
  if (value === '' || value == null) return null
  const number = Number(value)
  return Number.isFinite(number) ? number : null
}

async function save() {
  if (submitting.value) return
  submitError.value = ''

  const name = form.name.trim()
  const dataSource = form.dataSource.trim()

  if (!name || name.length > 128) {
    ElMessage.warning('酒店名称应为 1-128 个字符')
    return
  }
  if (!dataSource || dataSource.length > 500) {
    ElMessage.warning('资料来源必填，1-500 个字符')
    return
  }

  const lon = coordinate(form.longitude)
  const lat = coordinate(form.latitude)
  if (lon != null && (lon < -180 || lon > 180)) {
    ElMessage.warning('经度取值范围为 -180 至 180')
    return
  }
  if (lat != null && (lat < -90 || lat > 90)) {
    ElMessage.warning('纬度取值范围为 -90 至 90')
    return
  }

  const payload = {
    name,
    dataSource,
    address: optional(form.address),
    contactPhone: optional(form.contactPhone),
    longitude: lon,
    latitude: lat,
    intro: optional(form.intro),
    status: form.status
  }

  submitting.value = true
  try {
    const saved = props.hotel?.id
      ? await adminApi.updateHotel(props.hotel.id, payload)
      : await adminApi.createHotel(payload)
    ElMessage.success(props.hotel?.id ? '酒店资料已更新' : '酒店资料已创建')
    emit('saved', saved)
    close()
  } catch (cause) {
    submitError.value = cause.message || '保存失败，请稍后重试'
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
    width="min(720px, calc(100vw - 32px))"
    :close-on-click-modal="!submitting"
    :show-close="!submitting"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div class="dialog-form-grid">
      <p v-if="submitError" class="form-error wide" role="alert">{{ submitError }}</p>

      <div class="form-field wide">
        <label>酒店名称 <span class="req">*</span></label>
        <input v-model="form.name" maxlength="128" placeholder="例如：昆明测试酒店" />
      </div>

      <div class="form-field wide">
        <label>资料来源 <span class="req">*</span></label>
        <input v-model="form.dataSource" maxlength="500" placeholder="例如：团队测试数据" />
      </div>

      <div class="form-field">
        <label>联系电话</label>
        <input v-model="form.contactPhone" maxlength="20" placeholder="可选" />
      </div>

      <div class="form-field">
        <label>状态</label>
        <select v-model="form.status">
          <option value="ACTIVE">启用</option>
          <option value="DISABLED">停用</option>
        </select>
      </div>

      <div class="form-field wide">
        <label>详细地址</label>
        <input v-model="form.address" maxlength="255" placeholder="可选" />
      </div>

      <div class="form-field">
        <label>经度</label>
        <input v-model="form.longitude" type="number" step="0.0000001" placeholder="-180 ~ 180" />
      </div>

      <div class="form-field">
        <label>纬度</label>
        <input v-model="form.latitude" type="number" step="0.0000001" placeholder="-90 ~ 90" />
      </div>

      <div class="form-field wide">
        <label>简介</label>
        <textarea v-model="form.intro" rows="3" maxlength="10000" placeholder="酒店简介，可选"></textarea>
      </div>
    </div>

    <template #footer>
      <button class="secondary-button" :disabled="submitting" @click="close">取消</button>
      <button class="primary-button" :disabled="submitting" @click="save">
        {{ submitting ? '保存中…' : '保存' }}
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

.form-field {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.form-field label {
  font-size: 12px;
  color: var(--text-secondary);
}

.form-field input,
.form-field textarea,
.form-field select {
  padding: 8px 10px;
  border: 1px solid var(--border-divider, #e5e7eb);
  border-radius: 6px;
  font-family: inherit;
  font-size: 13px;
  background: white;
}

.form-field textarea {
  resize: vertical;
}

.req {
  color: var(--danger-red);
}

.form-error {
  margin: 0;
  color: var(--danger-red, #dc2626);
  font-size: 12px;
}

@media (max-width: 640px) {
  .dialog-form-grid {
    grid-template-columns: 1fr;
  }
}
</style>

<style>
@media (max-width: 640px) {
  .hotel-form-dialog {
    margin-top: 5dvh;
  }
  .hotel-form-dialog .el-dialog__body {
    max-height: 65dvh;
    overflow-y: auto;
  }
}
</style>