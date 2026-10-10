<script setup>
import { onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { adminApi } from '@/api/modules'
import RequestState from '@/components/RequestState.vue'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()

const props = defineProps({ mode: { type: String, default: 'users' } })

const rows = ref([])
const loading = ref(false)
const error = ref('')
const updating = ref(null)

// ---------- 员工表单弹窗 ----------
const dialogVisible = ref(false)
const saving = ref(false)
const formError = ref('')
const editingId = ref(null)

const form = reactive({
  username: '',
  password: '',
  realName: '',
  employeeNo: '',
  phone: '',
  department: '',
  position: ''
})

async function load() {
  loading.value = true
  error.value = ''
  try {
    const data =
      props.mode === 'staff'
        ? await adminApi.staff({ page: 1, size: 20 })
        : await adminApi.users({ page: 1, size: 20 })
    rows.value = data?.items || []
  } catch (cause) {
    error.value = cause.message || '账号列表加载失败'
    rows.value = []
  } finally {
    loading.value = false
  }
}

// ---------- 用户模式：启用/停用 ----------
async function toggleUserStatus(row) {
  if (updating.value || props.mode === 'staff') return
  const next = row.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE'
  updating.value = row.id
  try {
    const updated = await adminApi.updateUserStatus(row.id, next)
    row.status = updated?.status || next
    ElMessage.success(next === 'DISABLED' ? '账号已停用' : '账号已恢复')
  } catch (cause) {
    if (cause.status === 409) ElMessage.warning(cause.message || '当前状态不允许该操作')
    else ElMessage.error(cause.message || '状态更新失败')
  } finally {
    updating.value = null
  }
}

// ---------- 员工模式：创建/编辑/停用 ----------
function openCreate() {
  editingId.value = null
  formError.value = ''
  Object.assign(form, {
    username: '',
    password: '',
    realName: '',
    employeeNo: '',
    phone: '',
    department: '',
    position: ''
  })
  dialogVisible.value = true
}

function openEdit(row) {
  editingId.value = row.id
  formError.value = ''
  Object.assign(form, {
    username: row.username || '',
    password: '',
    realName: row.realName || '',
    employeeNo: row.employeeNo || '',
    phone: row.phone || '',
    department: row.department || '',
    position: row.position || ''
  })
  dialogVisible.value = true
}

function optional(value) {
  const trimmed = (value || '').trim()
  return trimmed === '' ? null : trimmed
}

async function saveStaff() {
  if (saving.value) return
  formError.value = ''
  const realName = form.realName.trim()
  const employeeNo = form.employeeNo.trim()

  if (!realName || realName.length > 64) {
    formError.value = '请填写真实姓名（1-64 字）'
    return
  }
  if (!employeeNo || employeeNo.length > 32) {
    formError.value = '请填写员工工号（1-32 字）'
    return
  }

  let payload
  if (editingId.value) {
    payload = {
      realName,
      employeeNo,
      phone: optional(form.phone),
      department: optional(form.department),
      position: optional(form.position)
    }
  } else {
    const username = form.username.trim()
    const password = form.password
    if (!/^[A-Za-z0-9_]{3,32}$/.test(username)) {
      formError.value = '用户名须为 3-32 位字母、数字或下划线'
      return
    }
    if (!password || password.length < 8 || password.length > 72) {
      formError.value = '密码长度须为 8-72 个字符'
      return
    }
    payload = {
      username,
      password,
      realName,
      employeeNo,
      phone: optional(form.phone),
      department: optional(form.department),
      position: optional(form.position)
    }
  }

  saving.value = true
  try {
    if (editingId.value) {
      await adminApi.updateStaff(editingId.value, payload)
      ElMessage.success('员工资料已更新')
    } else {
      await adminApi.createStaff(payload)
      ElMessage.success('员工账号已创建')
    }
    dialogVisible.value = false
    await load()
  } catch (cause) {
    formError.value = cause.message || '保存失败'
  } finally {
    saving.value = false
  }
}

async function toggleStaffStatus(row) {
  if (updating.value) return
  const isActive = row.status === 'ACTIVE'
  const next = isActive ? 'DISABLED' : 'ACTIVE'
  if (isActive) {
    try {
      await ElMessageBox.confirm(
        `确认停用员工「${row.realName}」吗？停用后该账号将无法登录。`,
        '停用员工',
        { type: 'warning', confirmButtonText: '确认停用', cancelButtonText: '取消' }
      )
    } catch {
      return
    }
  }
  updating.value = row.id
  try {
    const updated = await adminApi.updateStaffStatus(row.id, next)
    row.status = updated?.status || next
    ElMessage.success(next === 'DISABLED' ? '员工已停用' : '员工已恢复')
  } catch (cause) {
    if (cause.status === 409) ElMessage.warning(cause.message || '当前状态不允许该操作')
    else ElMessage.error(cause.message || '状态更新失败')
  } finally {
    updating.value = null
  }
}

watch(() => props.mode, () => {
  dialogVisible.value = false
  load()
})

onMounted(load)
</script>

<template>
  <div class="admin-users-page">
    <div class="admin-page-head">
      <div>
        <h2>{{ props.mode === 'staff' ? '内部员工权限管理' : '注册用户账户管理' }}</h2>
        <p>
          {{
            props.mode === 'staff'
              ? '创建与维护内部运营、客服人员账号，支持停用与恢复。'
              : '查看注册游客基础资料与账户状态，不直接接触敏感密码。'
          }}
        </p>
      </div>
      <!-- 契约：POST /admin/staff 仅 ADMIN -->
      <button
        v-if="props.mode === 'staff' && auth.hasRole('ADMIN')"
        class="primary-button"
        @click="openCreate"
      >
        + 新增员工
      </button>
    </div>

    <div class="admin-panel">
      <RequestState
        :loading="loading"
        :error="error"
        :empty="!loading && !error && rows.length === 0"
        :empty-text="props.mode === 'staff' ? '暂无员工数据。' : '暂无用户数据。'"
        @retry="load"
      >
        <table class="data-table">
          <thead>
            <tr v-if="props.mode === 'staff'">
              <th>员工工号</th>
              <th>姓名</th>
              <th>所属部门</th>
              <th>职级岗位</th>
              <th>联系电话</th>
              <th>状态</th>
              <th style="text-align: right;">操作</th>
            </tr>
            <tr v-else>
              <th>账号用户名</th>
              <th>用户昵称</th>
              <th>联系电话</th>
              <th>电子邮箱</th>
              <th>账号状态</th>
              <th>注册时间</th>
              <th style="text-align: right;">操作</th>
            </tr>
          </thead>
          <tbody>
            <template v-if="props.mode === 'staff'">
              <tr v-for="row in rows" :key="row.id">
                <td><strong>{{ row.employeeNo }}</strong></td>
                <td>{{ row.realName }}</td>
                <td>{{ row.department || '—' }}</td>
                <td>{{ row.position || '—' }}</td>
                <td>{{ row.phone || '—' }}</td>
                <td>
                  <span class="tag" :class="row.status === 'ACTIVE' ? 'success' : 'danger'">
                    {{ row.status === 'ACTIVE' ? '正常' : '已停用' }}
                  </span>
                </td>
                <td style="text-align: right;" class="row-actions">
                  <!-- 契约：PUT /admin/staff/{staffId} 未限制角色，STAFF 与 ADMIN 均可编辑 -->
                  <button type="button" class="text-button" @click="openEdit(row)">编辑</button>
                  <!-- 契约：PATCH /admin/staff/{staffId}/status 仅 ADMIN -->
                  <template v-if="auth.hasRole('ADMIN')">
                    <span class="divider">|</span>
                    <button
                      type="button"
                      class="text-button"
                      :class="{ 'text-danger': row.status === 'ACTIVE' }"
                      :disabled="updating === row.id"
                      @click="toggleStaffStatus(row)"
                    >
                      {{ row.status === 'ACTIVE' ? '停用' : '恢复' }}
                    </button>
                  </template>
                </td>
              </tr>
            </template>
            <template v-else>
              <tr v-for="row in rows" :key="row.id">
                <td><strong>{{ row.username }}</strong></td>
                <td>{{ row.nickname }}</td>
                <td>{{ row.phone || '—' }}</td>
                <td>{{ row.email || '—' }}</td>
                <td>
                  <span class="tag" :class="row.status === 'ACTIVE' ? 'success' : 'danger'">
                    {{ row.status === 'ACTIVE' ? '正常使用' : '已冻结' }}
                  </span>
                </td>
                <td>{{ row.createdAt }}</td>
                <td style="text-align: right;">
                  <!-- 契约：PATCH /admin/users/{userId}/status 仅 ADMIN -->
                  <button
                    v-if="auth.hasRole('ADMIN')"
                    type="button"
                    class="text-button"
                    :class="{ 'text-danger': row.status === 'ACTIVE' }"
                    :disabled="updating === row.id"
                    @click="toggleUserStatus(row)"
                  >
                    {{ row.status === 'ACTIVE' ? '停用' : '恢复' }}
                  </button>
                  <span v-else class="muted-text">—</span>
                </td>
              </tr>
            </template>
          </tbody>
        </table>
      </RequestState>
    </div>

    <!-- 员工创建/编辑弹窗 -->
    <el-dialog
      v-model="dialogVisible"
      :title="editingId ? '编辑员工资料' : '新增员工账号'"
      width="min(640px, calc(100vw - 32px))"
      :close-on-click-modal="!saving"
    >
      <div class="dialog-form-grid">
        <p v-if="formError" class="form-error wide" role="alert">{{ formError }}</p>

        <template v-if="!editingId">
          <div class="form-field">
            <label>登录用户名 <span class="req">*</span></label>
            <input v-model="form.username" maxlength="32" placeholder="3-32 位字母数字下划线" />
          </div>
          <div class="form-field">
            <label>初始密码 <span class="req">*</span></label>
            <input v-model="form.password" type="password" maxlength="72" placeholder="8-72 个字符" />
          </div>
        </template>

        <div class="form-field">
          <label>真实姓名 <span class="req">*</span></label>
          <input v-model="form.realName" maxlength="64" placeholder="例如：王顾问" />
        </div>
        <div class="form-field">
          <label>员工工号 <span class="req">*</span></label>
          <input v-model="form.employeeNo" maxlength="32" placeholder="例如：EMP001" />
        </div>
        <div class="form-field">
          <label>联系电话</label>
          <input v-model="form.phone" maxlength="20" placeholder="可选" />
        </div>
        <div class="form-field">
          <label>所属部门</label>
          <input v-model="form.department" maxlength="64" placeholder="可选" />
        </div>
        <div class="form-field wide">
          <label>职级岗位</label>
          <input v-model="form.position" maxlength="64" placeholder="可选" />
        </div>
      </div>
      <template #footer>
        <button class="secondary-button" :disabled="saving" @click="dialogVisible = false">取消</button>
        <button class="primary-button" :disabled="saving" @click="saveStaff">
          {{ saving ? '保存中…' : '保存' }}
        </button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.row-actions {
  white-space: nowrap;
}

.divider {
  color: var(--border-strong);
  margin: 0 6px;
  font-size: 11px;
}

.text-danger {
  color: var(--danger-red) !important;
}

.muted-text {
  color: var(--text-tertiary);
  font-size: 12px;
}

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

.form-field input {
  padding: 8px 10px;
  border: 1px solid var(--border-divider, #e5e7eb);
  border-radius: 6px;
  font-family: inherit;
  font-size: 13px;
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