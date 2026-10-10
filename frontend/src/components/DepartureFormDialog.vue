<script setup>
import { reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { adminApi } from '@/api/modules'

/**
 * 团期表单弹窗（新增 / 修改共用）。
 * 字段严格遵循契约 DepartureUpsertRequest；金额格式必须匹配 ^(0|[1-9][0-9]*)\.[0-9]{2}$。
 */
const props = defineProps({
  modelValue: { type: Boolean, default: false },
  departure: { type: Object, default: null }
})
const emit = defineEmits(['update:modelValue', 'saved'])

const submitting = ref(false)
const submitError = ref('')
const optionsLoaded = ref(false)
const routes = ref([])
const guides = ref([])

const form = reactive({
  routeId: '',
  startDate: '',
  endDate: '',
  adultPrice: '',
  childPrice: '',
  maxPeople: 20,
  guideId: ''
})

function reset() {
  const source = props.departure || {}
  Object.assign(form, {
    routeId: source.routeId ? String(source.routeId) : '',
    startDate: source.startDate || '',
    endDate: source.endDate || '',
    adultPrice: source.adultPrice || '',
    childPrice: source.childPrice || '',
    maxPeople: source.maxPeople || 20,
    guideId: source.guideId ? String(source.guideId) : ''
  })
  submitError.value = ''
}

watch(
  () => [props.modelValue, props.departure],
  () => {
    if (props.modelValue) {
      reset()
      loadOptions()
    }
  },
  { immediate: true }
)

async function loadOptions() {
  if (optionsLoaded.value) return
  optionsLoaded.value = true
  try {
    const [routePage, guidePage] = await Promise.all([
      adminApi.routes({ page: 1, size: 100 }),
      adminApi.guides({ page: 1, size: 100 })
    ])
    routes.value = routePage?.items || []
    guides.value = guidePage?.items || []
  } catch {
    routes.value = []
    guides.value = []
  }
}

function close() {
  emit('update:modelValue', false)
}

/** 金额必须是 "数字.两位小数"，与契约 Money 一致。 */
function normalizeMoney(value) {
  const trimmed = (value || '').toString().trim()
  if (!trimmed) return null
  if (/^(0|[1-9][0-9]*)\.[0-9]{2}$/.test(trimmed)) return trimmed
  const number = Number(trimmed)
  if (!Number.isFinite(number) || number < 0) return null
  return number.toFixed(2)
}

async function save() {
  if (submitting.value) return
  submitError.value = ''

  const routeId = form.routeId ? String(form.routeId) : ''
  const startDate = form.startDate
  const endDate = form.endDate
  const maxPeople = Number(form.maxPeople)
  const adultPrice = normalizeMoney(form.adultPrice)
  const childPrice = normalizeMoney(form.childPrice)

  if (!routeId) {
    ElMessage.warning('请选择所属线路')
    return
  }
  if (!startDate || !endDate) {
    ElMessage.warning('请填写出发和返程日期')
    return
  }
  if (endDate < startDate) {
    ElMessage.warning('返程日期不能早于出发日期')
    return
  }
  if (!adultPrice) {
    ElMessage.warning('成人价格格式不正确，应形如 2999.00')
    return
  }
  if (!childPrice) {
    ElMessage.warning('儿童价格格式不正确，应形如 1999.00')
    return
  }
  if (!Number.isInteger(maxPeople) || maxPeople < 1) {
    ElMessage.warning('最大人数应为大于 0 的整数')
    return
  }

  const payload = {
    routeId,
    startDate,
    endDate,
    adultPrice,
    childPrice,
    maxPeople,
    guideId: form.guideId ? String(form.guideId) : null
  }

  submitting.value = true
  try {
    const saved = props.departure?.id
      ? await adminApi.updateDeparture(props.departure.id, payload)
      : await adminApi.createDeparture(payload)
    ElMessage.success(props.departure?.id ? '团期已更新' : '团期已创建')
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
    class="departure-form-dialog"
    :model-value="modelValue"
    :title="departure?.id ? '编辑团期' : '新增团期'"
    width="min(720px, calc(100vw - 32px))"
    :close-on-click-modal="!submitting"
    :show-close="!submitting"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div class="dialog-form-grid">
      <p v-if="submitError" class="form-error wide" role="alert">{{ submitError }}</p>

      <div class="form-field wide">
        <label>所属线路 <span class="req">*</span></label>
        <select v-model="form.routeId">
          <option value="">请选择线路</option>
          <option v-for="route in routes" :key="route.id" :value="String(route.id)">
            #{{ route.id }} · {{ route.name }}（{{ route.departureCity }} → {{ route.destination }}）
          </option>
        </select>
      </div>

      <div class="form-field">
        <label>出发日期 <span class="req">*</span></label>
        <input v-model="form.startDate" type="date" />
      </div>

      <div class="form-field">
        <label>返程日期 <span class="req">*</span></label>
        <input v-model="form.endDate" type="date" />
      </div>

      <div class="form-field">
        <label>成人价格 <span class="req">*</span></label>
        <input v-model="form.adultPrice" placeholder="例如：2999.00" />
      </div>

      <div class="form-field">
        <label>儿童价格 <span class="req">*</span></label>
        <input v-model="form.childPrice" placeholder="例如：1999.00" />
      </div>

      <div class="form-field">
        <label>最大人数 <span class="req">*</span></label>
        <input v-model.number="form.maxPeople" type="number" min="1" />
      </div>

      <div class="form-field">
        <label>分配导游</label>
        <select v-model="form.guideId">
          <option value="">暂不分配</option>
          <option v-for="guide in guides" :key="guide.id" :value="String(guide.id)">
            {{ guide.name }}（{{ guide.phone }}）
          </option>
        </select>
      </div>

      <p class="form-hint wide">
        金额格式必须为「数字.两位小数」，与后端契约一致。团期创建后默认状态由后端决定。
      </p>
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
.form-field select {
  padding: 8px 10px;
  border: 1px solid var(--border-divider, #e5e7eb);
  border-radius: 6px;
  font-family: inherit;
  font-size: 13px;
  background: white;
}

.form-hint {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--text-secondary);
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
  .departure-form-dialog {
    margin-top: 5dvh;
  }
  .departure-form-dialog .el-dialog__body {
    max-height: 65dvh;
    overflow-y: auto;
  }
}
</style>