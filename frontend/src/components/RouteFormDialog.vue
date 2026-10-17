<script setup>
import { reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { adminApi } from '@/api/modules'
import { codePointLength, overCodePoints } from '@/utils/text'

/**
 * 线路基本资料表单弹窗（新增 / 修改共用）。
 *
 * 只提交契约 RouteUpsertRequest 允许的字段：状态、评分、报名人次都由后端决定，
 * 前端不提交也无法提交。可选字段留空时提交 null，使 PUT 能真正清空历史内容。
 *
 * 文本长度按 **Unicode 码点** 校验（与契约 maxLength、后端 @CodePointLength、库内
 * utf8mb4 VARCHAR(N) 同口径），输入框 **不设 maxlength**：HTML 的 maxlength 数的是
 * UTF-16 码元，一个 emoji 会被算成 2，200 个 emoji 的线路名会被静默截断成 100 个。
 * 改为「不截断 + 实时码点计数（超出变红）+ 提交时校验」。
 * 此前五个长文本字段只有 maxlength、没有校验：去掉截断后必须自己拦，否则超长内容
 * 只会在提交时收到一个 422。
 */
const props = defineProps({
  modelValue: { type: Boolean, default: false },
  route: { type: Object, default: null }
})
const emit = defineEmits(['update:modelValue', 'saved'])

const submitting = ref(false)
const submitError = ref('')

/** 各字段的码点上限，与契约 RouteUpsertRequest 和后端 @CodePointLength 一致（模板计数器共用一份）。 */
const NAME_MIN = 2
const NAME_MAX = 200
const DEPARTURE_CITY_MAX = 64
const DESTINATION_MAX = 255
const COVER_URL_MAX = 500
const LONG_TEXT_MAX = 10000

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

/**
 * 提交前校验：与后端 RouteUpsertRequest 的约束（必填、长度区间、天数范围）一致。
 * 长度按码点计数，长文本字段此前完全没有校验，这里一并补上（上限取契约 maxLength）。
 */
function validate(durationDays) {
  const name = form.name.trim()
  const departureCity = form.departureCity.trim()
  const destination = form.destination.trim()
  if (codePointLength(name) < NAME_MIN || overCodePoints(name, NAME_MAX)) {
    return `线路名称长度应为 ${NAME_MIN}-${NAME_MAX} 个字符`
  }
  if (!departureCity || overCodePoints(departureCity, DEPARTURE_CITY_MAX)) {
    return `请填写出发城市（不超过 ${DEPARTURE_CITY_MAX} 个字符）`
  }
  if (!destination || overCodePoints(destination, DESTINATION_MAX)) {
    return `请填写目的地（不超过 ${DESTINATION_MAX} 个字符）`
  }
  if (!Number.isInteger(durationDays) || durationDays < 1 || durationDays > 365) {
    return '行程天数应为 1-365 之间的整数'
  }
  if (overCodePoints(form.description, LONG_TEXT_MAX)) return `线路简介不能超过 ${LONG_TEXT_MAX} 个字符`
  if (overCodePoints(form.coverUrl, COVER_URL_MAX)) return `封面图地址不能超过 ${COVER_URL_MAX} 个字符`
  if (overCodePoints(form.included, LONG_TEXT_MAX)) return `费用包含不能超过 ${LONG_TEXT_MAX} 个字符`
  if (overCodePoints(form.excluded, LONG_TEXT_MAX)) return `费用不含不能超过 ${LONG_TEXT_MAX} 个字符`
  if (overCodePoints(form.bookingNotice, LONG_TEXT_MAX)) return `报名须知不能超过 ${LONG_TEXT_MAX} 个字符`
  return ''
}

async function save() {
  if (submitting.value) return
  submitError.value = ''
  const durationDays = Number(form.durationDays)
  const invalid = validate(durationDays)
  if (invalid) return ElMessage.warning(invalid)

  const payload = {
    name: form.name.trim(),
    departureCity: form.departureCity.trim(),
    destination: form.destination.trim(),
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
        <!-- 不设 maxlength：HTML 数的是 UTF-16 码元，会把契约允许的 emoji 名称静默截断 -->
        <input v-model="form.name" placeholder="例如：昆明·大理·丽江 6 日跟团游" />
        <p class="form-counter" :class="{ over: overCodePoints(form.name, NAME_MAX) }">
          {{ codePointLength(form.name) }} / {{ NAME_MAX }}
        </p>
      </div>

      <div class="form-field">
        <label>出发城市 <span class="req">*</span></label>
        <input v-model="form.departureCity" placeholder="例如：上海" />
        <p class="form-counter" :class="{ over: overCodePoints(form.departureCity, DEPARTURE_CITY_MAX) }">
          {{ codePointLength(form.departureCity) }} / {{ DEPARTURE_CITY_MAX }}
        </p>
      </div>

      <div class="form-field">
        <label>目的地 <span class="req">*</span></label>
        <input v-model="form.destination" placeholder="例如：云南" />
        <p class="form-counter" :class="{ over: overCodePoints(form.destination, DESTINATION_MAX) }">
          {{ codePointLength(form.destination) }} / {{ DESTINATION_MAX }}
        </p>
      </div>

      <div class="form-field">
        <label>行程天数 <span class="req">*</span></label>
        <input v-model.number="form.durationDays" type="number" min="1" max="365" />
      </div>

      <div class="form-field">
        <label>封面图地址</label>
        <input v-model="form.coverUrl" placeholder="https://..." />
        <p class="form-counter" :class="{ over: overCodePoints(form.coverUrl, COVER_URL_MAX) }">
          {{ codePointLength(form.coverUrl) }} / {{ COVER_URL_MAX }}
        </p>
      </div>

      <div class="form-field wide">
        <label>线路简介</label>
        <textarea v-model="form.description" rows="3" placeholder="线路亮点与整体说明"></textarea>
        <p class="form-counter" :class="{ over: overCodePoints(form.description, LONG_TEXT_MAX) }">
          {{ codePointLength(form.description) }} / {{ LONG_TEXT_MAX }}
        </p>
      </div>

      <div class="form-field wide">
        <label>费用包含</label>
        <textarea v-model="form.included" rows="2" placeholder="交通、住宿、门票等"></textarea>
        <p class="form-counter" :class="{ over: overCodePoints(form.included, LONG_TEXT_MAX) }">
          {{ codePointLength(form.included) }} / {{ LONG_TEXT_MAX }}
        </p>
      </div>

      <div class="form-field wide">
        <label>费用不含</label>
        <textarea v-model="form.excluded" rows="2" placeholder="个人消费、单房差等"></textarea>
        <p class="form-counter" :class="{ over: overCodePoints(form.excluded, LONG_TEXT_MAX) }">
          {{ codePointLength(form.excluded) }} / {{ LONG_TEXT_MAX }}
        </p>
      </div>

      <div class="form-field wide">
        <label>报名须知</label>
        <textarea v-model="form.bookingNotice" rows="2" placeholder="集合方式、证件要求等"></textarea>
        <p class="form-counter" :class="{ over: overCodePoints(form.bookingNotice, LONG_TEXT_MAX) }">
          {{ codePointLength(form.bookingNotice) }} / {{ LONG_TEXT_MAX }}
        </p>
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

/* 实时码点计数：超过契约上限时变红，用户不用等提交才知道超了 */
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
