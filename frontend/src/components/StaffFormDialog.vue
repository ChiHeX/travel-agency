<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { adminApi } from '@/api/modules'
import { codePointLength, utf8Bytes } from '@/utils/text'

/**
 * 员工（工作人员）新增 / 修改表单弹窗（对应契约 POST /admin/staff 与 PUT /admin/staff/{staffId}）。
 *
 * <p>只提交契约允许的字段：</p>
 * <ul>
 *   <li>新增走 {@code StaffCreateRequest}：{@code username / password / realName / employeeNo / phone / department / position}，
 *       一次同时建档登录账号（STAFF 角色，由后端固定分配）与员工资料；账号名是登录凭据，创建后不可改；</li>
 *   <li>修改走 {@code StaffUpdateRequest}：{@code realName / employeeNo / phone / department / position}，
 *       账号名与密码都不在修改范围内（契约不允许改账号；改密码走用户本人账号安全流程）；</li>
 *   <li>{@code id / userId / status / createdAt / updatedAt} 都不由本表单提交：主键与审计时间由后端与数据库维护，
 *       而 {@code status}（启用 / 停用）是独立的 {@code PATCH /admin/staff/{staffId}/status} 端点，
 *       由列表里的「启用 / 停用」按钮触发，不混进资料表单。</li>
 * </ul>
 *
 * <p>按组长定稿的权限方案：<b>表单不显示角色选择器</b>。创建工作人员时后端固定分配 STAFF 角色，
 * 前端只负责提交账号与资料。</p>
 *
 * <p>页面校验只用于改善交互，最终由后端裁定；后端返回的 message 会就地展示。</p>
 *
 * 保存成功后 emit `saved`，载荷是 `{ staff, created }`：`staff` 是后端返回的员工，
 * `created` 表示这次走的是 POST（新建）还是 PUT（修改），由调用方决定刷新哪一页。
 */
const props = defineProps({
  modelValue: { type: Boolean, default: false },
  staff: { type: Object, default: null }
})
const emit = defineEmits(['update:modelValue', 'saved'])

const submitting = ref(false)
const formError = ref('')

/** 契约 StaffCreateRequest.username 的正则：3–32 位字母、数字或下划线。 */
const USERNAME_PATTERN = /^[A-Za-z0-9_]{3,32}$/
/** 密码字符数下限与两个上限（契约 maxLength 与 BCrypt 的 72 字节硬上限）。 */
const PASSWORD_MIN_CHARS = 8
const PASSWORD_MAX_CHARS = 72
const PASSWORD_MAX_BYTES = 72

/** 各文本字段的码点上限，与契约保持一致。 */
const REAL_NAME_MAX = 64
const EMPLOYEE_NO_MAX = 32
const PHONE_MAX = 20
const DEPARTMENT_MAX = 64
const POSITION_MAX = 64

const form = reactive({
  username: '',
  password: '',
  realName: '',
  employeeNo: '',
  phone: '',
  department: '',
  position: ''
})

/** 带 id 即视为修改（PUT）；否则是新增（POST）。 */
const editing = computed(() => Boolean(props.staff?.id))

function reset() {
  const source = props.staff || {}
  Object.assign(form, {
    username: source.username || '',
    // 密码不回填：契约里没有"修改员工密码"的入口，编辑时不提交它。
    password: '',
    realName: source.realName || '',
    employeeNo: source.employeeNo || '',
    phone: source.phone || '',
    department: source.department || '',
    position: source.position || ''
  })
  formError.value = ''
}

watch(() => [props.modelValue, props.staff], () => {
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

/** 密码是否同时满足字符数与 UTF-8 字节数两个上限；计数器据此变色。 */
const passwordWithinLimits = computed(() =>
  codePointLength(form.password) <= PASSWORD_MAX_CHARS
  && utf8Bytes(form.password) <= PASSWORD_MAX_BYTES)

function validate() {
  const realName = form.realName.trim()
  const employeeNo = form.employeeNo.trim()
  if (!editing.value) {
    const username = form.username.trim()
    if (!USERNAME_PATTERN.test(username)) {
      return '登录账号应为 3–32 位，只能包含字母、数字与下划线'
    }
    if (!form.password) return '请设置初始密码'
    if (codePointLength(form.password) < PASSWORD_MIN_CHARS) return '密码至少 8 位'
    if (codePointLength(form.password) > PASSWORD_MAX_CHARS || utf8Bytes(form.password) > PASSWORD_MAX_BYTES) {
      return '密码过长：不超过 72 个字符，且 UTF-8 编码不超过 72 字节（中文、emoji 每个字符约占 3–4 字节）'
    }
  }
  if (!realName) return '请填写真实姓名'
  if (codePointLength(realName) > REAL_NAME_MAX) return `真实姓名最多 ${REAL_NAME_MAX} 个字符`
  if (!employeeNo) return '请填写员工工号'
  if (codePointLength(employeeNo) > EMPLOYEE_NO_MAX) return `员工工号最多 ${EMPLOYEE_NO_MAX} 个字符`
  if (form.phone.trim() && codePointLength(form.phone.trim()) > PHONE_MAX) {
    return `联系电话最多 ${PHONE_MAX} 个字符`
  }
  if (form.department.trim() && codePointLength(form.department.trim()) > DEPARTMENT_MAX) {
    return `所属部门最多 ${DEPARTMENT_MAX} 个字符`
  }
  if (form.position.trim() && codePointLength(form.position.trim()) > POSITION_MAX) {
    return `职级岗位最多 ${POSITION_MAX} 个字符`
  }
  return ''
}

async function save() {
  if (submitting.value) return
  formError.value = ''
  const invalid = validate()
  if (invalid) return ElMessage.warning(invalid)

  const payload = editing.value
    ? {
        realName: form.realName.trim(),
        employeeNo: form.employeeNo.trim(),
        phone: optional(form.phone),
        department: optional(form.department),
        position: optional(form.position)
      }
    : {
        username: form.username.trim(),
        password: form.password,
        realName: form.realName.trim(),
        employeeNo: form.employeeNo.trim(),
        phone: optional(form.phone),
        department: optional(form.department),
        position: optional(form.position)
      }

  submitting.value = true
  try {
    const saved = editing.value
      ? await adminApi.updateStaff(props.staff.id, payload)
      : await adminApi.createStaff(payload)
    ElMessage.success(editing.value ? '员工资料已更新' : '员工账号已创建')
    emit('saved', { staff: saved, created: !editing.value })
    close()
  } catch (cause) {
    // 409（账号或工号重复）、422（字段语义）、403（非 ADMIN）的 message 都可读，就地展示。
    formError.value = cause.message || '员工保存失败，请稍后重试'
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-dialog
    append-to-body
    class="staff-form-dialog"
    :model-value="modelValue"
    :title="editing ? '编辑员工资料' : '新增员工账号'"
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
          <input v-model="form.username" autocomplete="off" placeholder="3–32 位字母、数字或下划线" />
        </div>

        <div class="form-field">
          <label>初始密码 <span class="req">*</span></label>
          <input
            v-model="form.password"
            type="password"
            autocomplete="new-password"
            placeholder="8–72 位，UTF-8 不超过 72 字节"
          />
          <p class="form-counter" :class="{ over: !passwordWithinLimits }">
            {{ codePointLength(form.password) }} / {{ PASSWORD_MAX_CHARS }} 字符 ·
            {{ utf8Bytes(form.password) }} / {{ PASSWORD_MAX_BYTES }} 字节
          </p>
        </div>
      </template>

      <!-- 修改：账号名只读展示；改密码不属于员工资料维护范围 -->
      <div v-else class="form-field wide">
        <label>登录账号</label>
        <input :value="form.username" disabled />
        <p class="form-hint">账号名是登录凭据，创建后不可修改；登录密码由员工本人在账号安全中自行修改。</p>
      </div>

      <div class="form-field">
        <label>真实姓名 <span class="req">*</span></label>
        <input v-model="form.realName" placeholder="例如：王顾问" />
        <p class="form-counter" :class="{ over: codePointLength(form.realName) > REAL_NAME_MAX }">
          {{ codePointLength(form.realName) }} / {{ REAL_NAME_MAX }}
        </p>
      </div>

      <div class="form-field">
        <label>员工工号 <span class="req">*</span></label>
        <input v-model="form.employeeNo" placeholder="例如：EMP001" />
        <p class="form-counter" :class="{ over: codePointLength(form.employeeNo) > EMPLOYEE_NO_MAX }">
          {{ codePointLength(form.employeeNo) }} / {{ EMPLOYEE_NO_MAX }}
        </p>
      </div>

      <div class="form-field">
        <label>联系电话</label>
        <input v-model="form.phone" placeholder="可选" />
        <p class="form-counter" :class="{ over: codePointLength(form.phone) > PHONE_MAX }">
          {{ codePointLength(form.phone) }} / {{ PHONE_MAX }}
        </p>
      </div>

      <div class="form-field">
        <label>所属部门</label>
        <input v-model="form.department" placeholder="可选，例如：运营部" />
        <p class="form-counter" :class="{ over: codePointLength(form.department) > DEPARTMENT_MAX }">
          {{ codePointLength(form.department) }} / {{ DEPARTMENT_MAX }}
        </p>
      </div>

      <div class="form-field wide">
        <label>职级岗位</label>
        <input v-model="form.position" placeholder="可选，例如：行程顾问" />
        <p class="form-counter" :class="{ over: codePointLength(form.position) > POSITION_MAX }">
          {{ codePointLength(form.position) }} / {{ POSITION_MAX }}
        </p>
      </div>

      <p class="form-hint wide">
        新增会同时创建登录账号（STAFF 角色）与员工资料；启用 / 停用请在列表里操作。
        角色由系统按账号类型固定分配，本表单不提供角色选择。
      </p>
    </div>

    <template #footer>
      <button class="secondary-button" :disabled="submitting" @click="close">取消</button>
      <button class="primary-button" :disabled="submitting" @click="save">
        {{ submitting ? '保存中…' : '保存员工' }}
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
  .staff-form-dialog { margin-top: 5dvh; }
  .staff-form-dialog .el-dialog__body { max-height: 65dvh; overflow-y: auto; }
}
</style>