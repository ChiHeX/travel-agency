<script setup>
import { reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { adminApi } from '@/api/modules'

/**
 * 线路基本资料表单弹窗（新增 / 修改共用）。
 *
 * 只提交契约 RouteUpsertRequest 允许的字段：状态、评分、报名人次都由后端决定，
 * 前端不提交也无法提交。可选字段留空时提交 null，使 PUT 能真正清空历史内容。
 */
const props = defineProps({
  modelValue: { type: Boolean, default: false },
  route: { type: Object, default: null }
})
const emit = defineEmits(['update:modelValue', 'saved'])

const submitting = ref(false)
const submitError = ref('')

const form = reactive({
  name: '',
  departureCity: '',
  destination: '',
  durationDays: 1,
  description: '',
  coverUrl: '',
  included: '',
  excluded: '',
  bookingNotice: ''
})

function reset() {
  const source = props.route || {}
  Object.assign(form, {
    name: source.name || '',
    departureCity: source.departureCity || '',
    destination: source.destination || '',
    durationDays: source.durationDays || 1,
    description: source.description || '',
    coverUrl: source.coverUrl || '',
    included: source.included || '',
    excluded: source.excluded || '',
    bookingNotice: source.bookingNotice || ''
  })
  submitError.value = ''
}

watch(() => [props.modelValue, props.route], () => {
  if (props.modelValue) reset()
}, { immediate: true })

function close() {
  emit('update:modelValue', false)
}

/** 空字符串按“未填写”处理，提交 null，避免后端把空白当有效内容保存。 */
function optional(value) {
  const trimmed = (value || '').trim()
  return trimmed === '' ? null : trimmed
}

async function save() {
  if (submitting.value) return
  submitError.value = ''
  const name = form.name.trim()
  const departureCity = form.departureCity.trim()
  const destination = form.destination.trim()
  const durationDays = Number(form.durationDays)
  if (name.length < 2 || name.length > 200) return ElMessage.warning('线路名称长度应为 2-200 个字符')
  if (!departureCity || departureCity.length > 64) return ElMessage.warning('请填写出发城市（不超过 64 个字符）')
  if (!destination || destination.length > 255) return ElMessage.warning('请填写目的地（不超过 255 个字符）')
  if (!Number.isInteger(durationDays) || durationDays < 1 || durationDays > 365) {
    return ElMessage.warning('行程天数应为 1-365 之间的整数')
  }

  const payload = {
    name,
    departureCity,
    destination,
    durationDays,
    description: optional(form.description),
    coverUrl: optional(form.coverUrl),
    included: optional(form.included),
    excluded: optional(form.excluded),
    bookingNotice: optional(form.bookingNotice)
  }

  submitting.value = true
  try {
    const saved = props.route?.id
      ? await adminApi.updateRoute(props.route.id, payload)
      : await adminApi.createRoute(payload)
    ElMessage.success(props.route?.id ? '线路资料已更新' : '线路草稿已创建，可继续维护行程后上架')
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
    class="route-form-dialog"
    :model-value="modelValue"
    :title="route?.id ? '编辑线路资料' : '新增跟团线路'"
    width="min(720px, calc(100vw - 32px))"
    :close-on-click-modal="!submitting"
    :show-close="!submitting"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div class="dialog-form-grid">
      <p v-if="submitError" class="form-error wide" role="alert">{{ submitError }}</p>

      <div class="form-field wide">
        <label>线路名称 <span class="req">*</span></label>
        <input v-model="form.name" maxlength="200" placeholder="例如：昆明·大理·丽江 6 日跟团游" />
      </div>

      <div class="form-field">
        <label>出发城市 <span class="req">*</span></label>
        <input v-model="form.departureCity" maxlength="64" placeholder="例如：上海" />
      </div>

      <div class="form-field">
        <label>目的地 <span class="req">*</span></label>
        <input v-model="form.destination" maxlength="255" placeholder="例如：云南" />
      </div>

      <div class="form-field">
        <label>行程天数 <span class="req">*</span></label>
        <input v-model.number="form.durationDays" type="number" min="1" max="365" />
      </div>

      <div class="form-field">
        <label>封面图地址</label>
        <input v-model="form.coverUrl" maxlength="500" placeholder="https://..." />
      </div>

      <div class="form-field wide">
        <label>线路简介</label>
        <textarea v-model="form.description" rows="3" maxlength="10000" placeholder="线路亮点与整体说明"></textarea>
      </div>

      <div class="form-field wide">
        <label>费用包含</label>
        <textarea v-model="form.included" rows="2" maxlength="10000" placeholder="交通、住宿、门票等"></textarea>
      </div>

      <div class="form-field wide">
        <label>费用不含</label>
        <textarea v-model="form.excluded" rows="2" maxlength="10000" placeholder="个人消费、单房差等"></textarea>
      </div>

      <div class="form-field wide">
        <label>报名须知</label>
        <textarea v-model="form.bookingNotice" rows="2" maxlength="10000" placeholder="集合方式、证件要求等"></textarea>
      </div>

      <p class="form-hint wide">
        线路创建后为“草稿”状态，维护至少一天行程后才能上架；上架/下架在列表或详情页操作。
      </p>
    </div>

    <template #footer>
      <button class="secondary-button" :disabled="submitting" @click="close">取消</button>
      <button class="primary-button" :disabled="submitting" @click="save">
        {{ submitting ? '保存中…' : '保存线路' }}
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
  .route-form-dialog { margin-top: 5dvh; }
  .route-form-dialog .el-dialog__body { max-height: 65dvh; overflow-y: auto; }
}
</style>
