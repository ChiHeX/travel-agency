<script setup>
import { reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { adminApi } from '@/api/modules'

/**
 * 团期新增 / 修改表单弹窗（对应契约 POST /admin/departures 与 PUT /admin/departures/{departureId}）。
 *
 * 只提交契约 DepartureUpsertRequest 允许的字段：`status`、`reservedPeople`、`confirmedPeople`
 * 都不在前端提交范围内 —— 新建团期由后端固定为 DRAFT（先上架才能报名），
 * 名称计数由下单 / 支付 / 退款链路维护。提交这些字段会被后端严格模式直接拒绝（400）。
 *
 * 金额按契约 Money 提交十进制字符串（固定 2 位小数）；主键按契约 Id 提交字符串，
 * 避免 JavaScript 大整数精度丢失。页面校验只用于改善交互，最终由后端裁定。
 */
const props = defineProps({
  modelValue: { type: Boolean, default: false },
  departure: { type: Object, default: null },
  routes: { type: Array, default: () => [] },
  guides: { type: Array, default: () => [] }
})
const emit = defineEmits(['update:modelValue', 'saved'])

const submitting = ref(false)
const submitError = ref('')

const form = reactive({
  routeId: '',
  startDate: '',
  endDate: '',
  adultPrice: '',
  childPrice: '',
  maxPeople: 20,
  guideId: ''
})

/** 契约 Money：十进制字符串，固定两位小数。 */
function money(value) {
  if (value === null || value === undefined || value === '') return ''
  const number = Number(value)
  return Number.isFinite(number) ? number.toFixed(2) : ''
}

function reset() {
  const source = props.departure || {}
  Object.assign(form, {
    routeId: source.routeId ? String(source.routeId) : '',
    startDate: source.startDate || '',
    endDate: source.endDate || '',
    adultPrice: money(source.adultPrice),
    childPrice: money(source.childPrice),
    maxPeople: source.maxPeople || 20,
    guideId: source.guideId ? String(source.guideId) : ''
  })
  submitError.value = ''
}

watch(() => [props.modelValue, props.departure], () => {
  if (props.modelValue) reset()
}, { immediate: true })

function close() {
  emit('update:modelValue', false)
}

/**
 * 页面侧校验：与后端 DepartureUpsertRequest 的约束保持一致（必填、非负、2 位小数、最大 10 位整数）。
 * 目的是让运营在提交前就看到问题，而不是等接口回一个 422。
 */
function validate() {
  if (!form.routeId) return '请选择所属线路'
  if (!form.startDate || !form.endDate) return '请填写出发与返程日期'
  if (form.endDate < form.startDate) return '返程日期不能早于出发日期'

  const prices = [
    ['成人价格', form.adultPrice],
    ['儿童价格', form.childPrice]
  ]
  for (const [label, raw] of prices) {
    const text = String(raw).trim()
    if (!text) return `请填写${label}`
    if (!/^\d{1,10}(\.\d{1,2})?$/.test(text)) return `${label}应为非负数，最多 10 位整数与 2 位小数`
  }

  const maxPeople = Number(form.maxPeople)
  if (!Number.isInteger(maxPeople) || maxPeople < 1) return '最大人数应为大于 0 的整数'
  return ''
}

async function save() {
  if (submitting.value) return
  submitError.value = ''
  const invalid = validate()
  if (invalid) return ElMessage.warning(invalid)

  // 主键按契约 Id 提交字符串；金额提交固定两位小数的字符串。
  const payload = {
    routeId: String(form.routeId),
    startDate: form.startDate,
    endDate: form.endDate,
    adultPrice: money(form.adultPrice),
    childPrice: money(form.childPrice),
    maxPeople: Number(form.maxPeople),
    guideId: form.guideId ? String(form.guideId) : null
  }

  submitting.value = true
  try {
    const saved = props.departure?.id
      ? await adminApi.updateDeparture(props.departure.id, payload)
      : await adminApi.createDeparture(payload)
    ElMessage.success(props.departure?.id
      ? '团期已更新'
      : '团期草稿已创建，请到列表里“开放报名”后才会对外售卖')
    emit('saved', saved)
    close()
  } catch (cause) {
    // 409（导游时间冲突）与 422（字段语义）由后端给出可读 message，直接展示在原地，不重复弹窗。
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
    width="min(680px, calc(100vw - 32px))"
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
            {{ route.name }}（{{ route.departureCity }} → {{ route.destination }}）
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
        <input v-model="form.adultPrice" inputmode="decimal" placeholder="例如：2999.00" />
      </div>

      <div class="form-field">
        <label>儿童价格 <span class="req">*</span></label>
        <input v-model="form.childPrice" inputmode="decimal" placeholder="例如：1999.00" />
      </div>

      <div class="form-field">
        <label>最大人数 <span class="req">*</span></label>
        <input v-model.number="form.maxPeople" type="number" min="1" />
      </div>

      <div class="form-field">
        <label>带团导游</label>
        <select v-model="form.guideId">
          <option value="">暂不分配</option>
          <option v-for="guide in guides" :key="guide.id" :value="String(guide.id)">{{ guide.name }}</option>
        </select>
      </div>

      <p class="form-hint wide">
        新建团期为“草稿”状态，需要在列表中改为“报名中”才会对用户开放报名。
        同一导游在同一时间范围内不允许带两个团（后端返回 409）。
      </p>
    </div>

    <template #footer>
      <button class="secondary-button" :disabled="submitting" @click="close">取消</button>
      <button class="primary-button" :disabled="submitting" @click="save">
        {{ submitting ? '保存中…' : '保存团期' }}
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
  .departure-form-dialog { margin-top: 5dvh; }
  .departure-form-dialog .el-dialog__body { max-height: 65dvh; overflow-y: auto; }
}
</style>
