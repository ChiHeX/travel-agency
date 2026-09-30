<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { adminApi } from '@/api/modules'

/**
 * 导游新增 / 修改表单弹窗（对应契约 POST /admin/guides 与 PUT /admin/guides/{guideId}）。
 *
 * <p>只提交契约允许的字段：</p>
 * <ul>
 *   <li>新增走 {@code GuideCreateRequest}：{@code username / password / name / phone / intro}
 *       —— 一次同时建档登录账号（GUIDE 角色）与导游资料，账号名是登录凭据，创建后不可改；</li>
 *   <li>修改走 {@code GuideUpdateRequest}：{@code name / phone / intro}，账号名不在修改范围内
 *       （契约不允许改账号，改密码走用户自己的账号安全流程）；</li>
 *   <li>{@code id / userId / status / createdAt / updatedAt} 都不由本表单提交：主键与审计时间由后端与数据库维护，
 *       而 {@code status}（启用 / 停用）是独立的 {@code PATCH /admin/guides/{guideId}/status} 端点
 *       （且仅 ADMIN 可用），由列表里的「启用 / 停用」按钮触发，不混进资料表单 ——
 *       提交这些字段会被后端严格模式拒绝（400）。</li>
 * </ul>
 *
 * <p>页面校验只用于改善交互，最终由后端裁定；后端返回的 message 会就地展示。</p>
 *
 * 保存成功后 emit `saved`，载荷是 `{ guide, created }`：`guide` 是后端返回的导游，
 * `created` 表示这次走的是 POST（新建）还是 PUT（修改），由调用方决定刷新哪一页。
 */
const props = defineProps({
  modelValue: { type: Boolean, default: false },
  guide: { type: Object, default: null }
})
const emit = defineEmits(['update:modelValue', 'saved'])

const submitting = ref(false)
const formError = ref('')

/** 契约 GuideCreateRequest.username 的正则：3–32 位字母、数字或下划线。 */
const USERNAME_PATTERN = /^[A-Za-z0-9_]{3,32}$/
/**
 * 密码上限有两个口径：契约 maxLength 是 72 个<b>字符</b>，而 BCrypt 的硬上限是 72 个
 * <b>UTF-8 字节</b> —— 25 个汉字只有 25 个字符却占 75 字节，能过字符校验却被 BCrypt 拒绝（后端 422）。
 * 两者都要在本地先拦一次，避免让运营白等一次必然失败的请求。
 */
const PASSWORD_MIN_CHARS = 8
const PASSWORD_MAX_CHARS = 72
const PASSWORD_MAX_BYTES = 72

const form = reactive({
  username: '',
  password: '',
  name: '',
  phone: '',
  intro: ''
})

/** 带 id 即视为修改（PUT）；否则是新增（POST）。 */
const editing = computed(() => Boolean(props.guide?.id))

function reset() {
  const source = props.guide || {}
  Object.assign(form, {
    username: source.username || '',
    // 密码不回填：契约里没有"修改导游密码"的入口，编辑时不提交它。
    password: '',
    name: source.name || '',
    phone: source.phone || '',
    intro: source.intro || ''
  })
  formError.value = ''
}

watch(() => [props.modelValue, props.guide], () => {
  if (props.modelValue) reset()
}, { immediate: true })

function close() {
  emit('update:modelValue', false)
}

/** 空串与纯空白一律按"未填写"处理，提交 null 而不是空字符串（契约允许 intro 为 null）。 */
function optional(value) {
  const text = String(value ?? '').trim()
  return text === '' ? null : text
}

/**
 * 按 Unicode 码点计数。契约的 maxLength 是 JSON Schema 口径，数的是字符（码点），
 * 后端的 @CodePointLength 也是这个口径；JS 的 String#length 数的是 UTF-16 码元，
 * 一个 emoji 会被算成 2，用 `.length` 会把契约允许的内容误判成超长。
 */
const codePointLength = (value) => [...String(value ?? '')].length
const utf8Bytes = (value) => new TextEncoder().encode(String(value ?? '')).length

/**
 * 页面侧校验：与后端 GuideAccountRequest / GuideUpdateRequest 的约束保持一致
 * （必填、用户名正则、密码字符与字节两个上限、文本长度上限）。
 */
function validate() {
  const name = form.name.trim()
  const phone = form.phone.trim()
  if (!editing.value) {
    const username = form.username.trim()
    if (!USERNAME_PATTERN.test(username)) {
      return '登录账号应为 3–32 位，只能包含字母、数字与下划线'
    }
    if (!form.password) return '请设置初始密码'
    if (form.password.length < PASSWORD_MIN_CHARS) return '密码至少 8 位'
    if (form.password.length > PASSWORD_MAX_CHARS || utf8Bytes(form.password) > PASSWORD_MAX_BYTES) {
      return '密码过长：不超过 72 个字符，且 UTF-8 编码不超过 72 字节（中文、emoji 每个字符约占 3–4 字节）'
    }
  }
  if (!name) return '请填写导游姓名'
  if (codePointLength(name) > 64) return '导游姓名最多 64 个字符'
  if (!phone) return '请填写联系电话'
  if (codePointLength(phone) < 3 || codePointLength(phone) > 20) return '联系电话长度应为 3–20 个字符'
  if (codePointLength(form.intro) > 1000) return '个人简介最多 1000 个字符'
  return ''
}

async function save() {
  if (submitting.value) return
  formError.value = ''
  const invalid = validate()
  if (invalid) return ElMessage.warning(invalid)

  const payload = editing.value
    ? { name: form.name.trim(), phone: form.phone.trim(), intro: optional(form.intro) }
    : {
        username: form.username.trim(),
        password: form.password,
        name: form.name.trim(),
        phone: form.phone.trim(),
        intro: optional(form.intro)
      }

  submitting.value = true
  try {
    const saved = editing.value
      ? await adminApi.updateGuide(props.guide.id, payload)
      : await adminApi.createGuide(payload)
    ElMessage.success(editing.value ? '导游资料已更新' : '导游账号已创建')
    // 带上 created：POST 与 PUT 对列表的影响不同 —— 新建的导游排在第一页，
    // 修改的导游留在原来的位置（created_at 不变），页面据此决定刷新哪一页。
    emit('saved', { guide: saved, created: !editing.value })
    close()
  } catch (cause) {
    // 409（账号已存在）、422（字段语义）、403（非 ADMIN）的 message 都可读，就地展示，不关闭弹窗、不丢输入。
    formError.value = cause.message || '导游保存失败，请稍后重试'
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-dialog
    append-to-body
    class="guide-form-dialog"
    :model-value="modelValue"
    :title="editing ? '编辑导游资料' : '新增导游账号'"
    width="min(680px, calc(100vw - 32px))"
    :close-on-click-modal="!submitting"
    :show-close="!submitting"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div class="dialog-form-grid">
      <p v-if="formError" class="form-error wide" role="alert">{{ formError }}</p>

      <!-- 新增：账号名与初始密码是登录凭据，建档后账号名不可修改 -->
      <template v-if="!editing">
        <div class="form-field">
          <label>登录账号 <span class="req">*</span></label>
          <input v-model="form.username" maxlength="32" autocomplete="off" placeholder="3–32 位字母、数字或下划线" />
        </div>

        <div class="form-field">
          <label>初始密码 <span class="req">*</span></label>
          <input
            v-model="form.password"
            type="password"
            maxlength="72"
            autocomplete="new-password"
            placeholder="8–72 位，UTF-8 不超过 72 字节"
          />
        </div>
      </template>

      <!-- 修改：账号名只读展示；改密码不属于导游资料维护范围 -->
      <div v-else class="form-field wide">
        <label>登录账号</label>
        <input :value="form.username" disabled />
        <p class="form-hint">账号名是登录凭据，创建后不可修改；登录密码由导游本人在账号安全中自行修改。</p>
      </div>

      <div class="form-field">
        <label>导游姓名 <span class="req">*</span></label>
        <input v-model="form.name" maxlength="64" placeholder="例如：李导" />
      </div>

      <div class="form-field">
        <label>联系电话 <span class="req">*</span></label>
        <input v-model="form.phone" maxlength="20" placeholder="例如：13800138001" />
      </div>

      <div class="form-field wide">
        <label>个人简介</label>
        <textarea v-model="form.intro" rows="3" maxlength="1000" placeholder="带团经验、擅长线路等，供后台核对与参考"></textarea>
      </div>

      <p class="form-hint wide">
        新增会同时创建登录账号（GUIDE 角色）与导游档案；启用 / 停用请在列表里操作。
        被指派团期的导游在明显冲突的时间范围内不能再被安排第二个团期，安排团期时后端会校验。
      </p>
    </div>

    <template #footer>
      <button class="secondary-button" :disabled="submitting" @click="close">取消</button>
      <button class="primary-button" :disabled="submitting" @click="save">
        {{ submitting ? '保存中…' : '保存导游' }}
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
  .guide-form-dialog { margin-top: 5dvh; }
  .guide-form-dialog .el-dialog__body { max-height: 65dvh; overflow-y: auto; }
}
</style>
