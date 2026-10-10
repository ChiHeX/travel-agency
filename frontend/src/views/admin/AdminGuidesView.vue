<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { adminApi } from '@/api/modules'
import RequestState from '@/components/RequestState.vue'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()

const rows = ref([])
const loading = ref(false)
const error = ref('')
const updating = ref(null)

// ---------- 导游表单弹窗 ----------
const dialogVisible = ref(false)
const saving = ref(false)
const formError = ref('')
const editingId = ref(null)

const form = reactive({
  username: '',
  password: '',
  name: '',
  phone: '',
  intro: ''
})

async function load() {
  loading.value = true
  error.value = ''
  try {
    const data = await adminApi.guides({ page: 1, size: 50 })
    rows.value = data?.items || []
  } catch (cause) {
    error.value = cause.message || '导游列表加载失败'
    rows.value = []
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editingId.value = null
  formError.value = ''
  Object.assign(form, { username: '', password: '', name: '', phone: '', intro: '' })
  dialogVisible.value = true
}

function openEdit(row) {
  editingId.value = row.id
  formError.value = ''
  Object.assign(form, {
    username: row.username || '',
    password: '',
    name: row.name || '',
    phone: row.phone || '',
    intro: row.intro || ''
  })
  dialogVisible.value = true
}

function optional(value) {
  const trimmed = (value || '').trim()
  return trimmed === '' ? null : trimmed
}

async function saveGuide() {
  if (saving.value) return
  formError.value = ''
  const name = form.name.trim()
  const phone = form.phone.trim()
  if (!name || name.length > 64) {
    formError.value = '请填写导游姓名（1-64 字）'
    return
  }
  if (!phone || phone.length < 3 || phone.length > 20) {
    formError.value = '请填写联系电话（3-20 字）'
    return
  }

  let payload
  if (editingId.value) {
    payload = {
      name,
      phone,
      intro: optional(form.intro)
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
      name,
      phone,
      intro: optional(form.intro)
    }
  }

  saving.value = true
  try {
    if (editingId.value) {
      await adminApi.updateGuide(editingId.value, payload)
      ElMessage.success('导游资料已更新')
    } else {
      await adminApi.createGuide(payload)
      ElMessage.success('导游账号已创建')
    }
    dialogVisible.value = false
    await load()
  } catch (cause) {
    formError.value = cause.message || '保存失败'
  } finally {
    saving.value = false
  }
}

async function toggleStatus(row) {
  if (updating.value) return
  const isActive = row.status === 'ACTIVE'
  const next = isActive ? 'DISABLED' : 'ACTIVE'
  if (isActive) {
    try {
      await ElMessageBox.confirm(
        `确认停用导游「${row.name}」吗？停用后该账号将无法登录导游工作台。`,
        '停用导游',
        { type: 'warning', confirmButtonText: '确认停用', cancelButtonText: '取消' }
      )
    } catch {
      return
    }
  }
  updating.value = row.id
  try {
    const updated = await adminApi.updateGuideStatus(row.id, next)
    row.status = updated?.status || next
    ElMessage.success(next === 'DISABLED' ? '导游已停用' : '导游已恢复')
  } catch (cause) {
    if (cause.status === 409) ElMessage.warning(cause.message || '当前状态不允许该操作')
    else ElMessage.error(cause.message || '状态更新失败')
  } finally {
    updating.value = null
  }
}

onMounted(load)
</script>

<template>
  <div class="admin-guides-page">
    <div class="admin-page-head">
      <div>
        <h2>导游团队管理</h2>
        <p>维护导游账号与基础资料，支持启用/停用；停用后导游无法登录工作台。</p>
      </div>
      <!-- 契约：POST /admin/guides 仅 ADMIN 可调用 -->
      <button v-if="auth.hasRole('ADMIN')" class="primary-button" @click="openCreate">
        + 新增导游
      </button>
    </div>

    <div class="admin-panel">
      <RequestState
        :loading="loading"
        :error="error"
        :empty="!loading && !error && rows.length === 0"
        empty-text="暂无导游数据。"
        @retry="load"
      >
        <table class="data-table">
          <thead>
            <tr>
              <th>导游姓名</th>
              <th>登录账号</th>
              <th>联系电话</th>
              <th>个人专长简介</th>
              <th>状态</th>
              <th style="text-align: right;">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in rows" :key="row.id">
              <td><strong>{{ row.name }}</strong></td>
              <td>{{ row.username }}</td>
              <td>{{ row.phone || '—' }}</td>
              <td class="intro-cell">{{ row.intro || '—' }}</td>
              <td>
                <span class="tag" :class="row.status === 'ACTIVE' ? 'success' : 'danger'">
                  {{ row.status === 'ACTIVE' ? '在岗' : '已停用' }}
                </span>
              </td>
              <td style="text-align: right;" class="row-actions">
                <!-- 契约：PUT /admin/guides/{guideId} 允许 STAFF 与 ADMIN -->
                <button type="button" class="text-button" @click="openEdit(row)">编辑</button>
                <!-- 契约：PATCH /admin/guides/{guideId}/status 仅 ADMIN -->
                <template v-if="auth.hasRole('ADMIN')">
                  <span class="divider">|</span>
                  <button
                    type="button"
                    class="text-button"
                    :class="{ 'text-danger': row.status === 'ACTIVE' }"
                    :disabled="updating === row.id"
                    @click="toggleStatus(row)"
                  >
                    {{ row.status === 'ACTIVE' ? '停用' : '恢复' }}
                  </button>
                </template>
              </td>
            </tr>
          </tbody>
        </table>
      </RequestState>
    </div>

    <!-- 导游创建/编辑弹窗 -->
    <el-dialog
      v-model="dialogVisible"
      :title="editingId ? '编辑导游资料' : '新增导游账号'"
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
          <label>导游姓名 <span class="req">*</span></label>
          <input v-model="form.name" maxlength="64" placeholder="例如：李导" />
        </div>
        <div class="form-field">
          <label>联系电话 <span class="req">*</span></label>
          <input v-model="form.phone" maxlength="20" placeholder="例如：13800138001" />
        </div>
        <div class="form-field wide">
          <label>个人专长简介</label>
          <textarea v-model="form.intro" rows="3" maxlength="1000" placeholder="可选，例如：具有云南线路带团经验"></textarea>
        </div>
      </div>
      <template #footer>
        <button class="secondary-button" :disabled="saving" @click="dialogVisible = false">取消</button>
        <button class="primary-button" :disabled="saving" @click="saveGuide">
          {{ saving ? '保存中…' : '保存' }}
        </button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.intro-cell {
  max-width: 280px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

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
.form-field textarea {
  padding: 8px 10px;
  border: 1px solid var(--border-divider, #e5e7eb);
  border-radius: 6px;
  font-family: inherit;
  font-size: 13px;
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