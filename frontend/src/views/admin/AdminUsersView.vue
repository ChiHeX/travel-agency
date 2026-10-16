<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { adminApi } from '@/api/modules'
import RequestState from '@/components/RequestState.vue'
import StaffFormDialog from '@/components/StaffFormDialog.vue'
import { codePointLength } from '@/utils/text'

const props = defineProps({ mode: { type: String, default: 'users' } })

const rows = ref([])
const loading = ref(false)
const error = ref('')
/** 正在提交状态变更的账号 id。 */
const updatingId = ref(null)
/** 正在加载详情的用户 id（仅用户模式用）。 */
const detailLoadingId = ref(null)

const query = reactive({ page: 1, size: 20, keyword: '', status: '' })
const total = ref(0)
const totalPages = ref(0)

const KEYWORD_MAX = 100

// ---------- 当前模式 ----------
const isUserMode = computed(() => props.mode === 'users')
const isStaffMode = computed(() => props.mode === 'staff')

// ---------- 状态字典 ----------
const STATUS_LABEL = { ACTIVE: '正常使用', DISABLED: '已冻结' }
const STAFF_STATUS_LABEL = { ACTIVE: '在岗', DISABLED: '已停用' }
const statusLabel = (status) => STATUS_LABEL[status] || status
const staffStatusLabel = (status) => STAFF_STATUS_LABEL[status] || status
const statusClass = (status) => (status === 'ACTIVE' ? 'success' : 'danger')

// ---------- 用户详情弹窗（仅用户模式） ----------
const detailVisible = ref(false)
const detail = ref(null)
const detailError = ref('')
const detailLoading = ref(false)

/**
 * 详情请求序号：只允许「当前打开的那次」写回弹窗。
 * 场景：打开用户 A、关闭后打开用户 B，A 的响应晚到后若无条件写回 detail，弹窗会跳回 A。
 */
let detailRequestSeq = 0
/** 当前弹窗正在查看的用户 id；关闭弹窗时置空。 */
const activeDetailUserId = ref(null)

// ---------- 员工表单弹窗（仅员工模式） ----------
const staffDialogVisible = ref(false)
const editingStaff = ref(null)

/**
 * 列表请求序号：只允许「最新一次」写回 rows / total / error / loading。
 *
 * <p>快速切换筛选、翻页、输入关键字后立刻提交时，会连续发出多个请求；返回顺序不保证与
 * 发出顺序一致。旧请求晚到成功会覆盖新筛选的结果（选中的筛选与列表对不上），
 * 晚到失败会清空新结果并弹出一条已经过时的错误；旧请求结束时若把 loading 关掉，
 * 还会让仍在途的新请求提前露出旧一页的数据。</p>
 */
let listRequestSeq = 0

async function load(retryOnEmptyPage = true) {
  const seq = ++listRequestSeq
  loading.value = true
  error.value = ''
  try {
    let result
    if (isUserMode.value) {
      result = await adminApi.users({
        page: query.page,
        size: query.size,
        keyword: query.keyword.trim() || undefined,
        status: query.status || undefined
      })
    } else {
      result = await adminApi.staff({
        page: query.page,
        size: query.size,
        keyword: query.keyword.trim() || undefined,
        status: query.status || undefined
      })
    }
    // 期间用户又改了筛选或翻页：这次响应已过期，丢弃
    if (seq !== listRequestSeq) return
    rows.value = result?.items || []
    total.value = Number(result?.total ?? rows.value.length)
    totalPages.value = Number(result?.totalPages ?? (rows.value.length ? 1 : 0))
    if (retryOnEmptyPage && !rows.value.length && query.page > 1) {
      query.page -= 1
      await load(false)
    }
  } catch (cause) {
    if (seq !== listRequestSeq) return
    rows.value = []
    error.value = cause.message || '账号列表加载失败'
  } finally {
    // 只有最新请求才有权结束加载态
    if (seq === listRequestSeq) loading.value = false
  }
}

function search() {
  const keyword = query.keyword.trim()
  if (codePointLength(keyword) > KEYWORD_MAX) {
    ElMessage.warning(`搜索关键字最多 ${KEYWORD_MAX} 个字符`)
    return
  }
  query.page = 1
  load()
}

function resetSearch() {
  query.keyword = ''
  query.status = ''
  query.page = 1
  load()
}

function goToPage(delta) {
  const next = query.page + delta
  if (next < 1 || (totalPages.value && next > totalPages.value)) return
  query.page = next
  load()
}

// ---------- 用户详情（仅用户模式） ----------
async function openDetail(row) {
  const seq = ++detailRequestSeq
  const userId = row.id
  detailVisible.value = true
  activeDetailUserId.value = userId
  detailLoading.value = true
  detailLoadingId.value = userId
  detailError.value = ''
  detail.value = null
  try {
    const data = await adminApi.user(userId)
    if (seq !== detailRequestSeq) return
    detail.value = data
  } catch (cause) {
    if (seq !== detailRequestSeq) return
    detailError.value = cause.message || '用户资料加载失败'
  } finally {
    if (seq === detailRequestSeq) {
      detailLoading.value = false
      detailLoadingId.value = null
    }
  }
}

function onDetailClose() {
  detailRequestSeq += 1
  activeDetailUserId.value = null
  detail.value = null
  detailLoading.value = false
  detailLoadingId.value = null
  detailError.value = ''
}

// ---------- 员工表单（仅员工模式） ----------
function openCreateStaff() {
  editingStaff.value = null
  staffDialogVisible.value = true
}

function openEditStaff(row) {
  editingStaff.value = row
  staffDialogVisible.value = true
}

function onStaffSaved(payload) {
  if (payload?.created) {
    query.page = 1
    load()
  } else {
    load()
  }
}

// ---------- 停用/恢复 ----------
/**
 * 用户账号停用/恢复。
 *
 * <p>停用与恢复都要确认：停用是"账号无法登录"，恢复是"重新允许登录"——
 * 两者都会改变账号的登录能力，误操作的影响对称，因此两边都要弹确认；取消时不发请求。</p>
 */
async function toggleUserStatus(row) {
  if (updatingId.value) return
  const isActive = row.status === 'ACTIVE'
  const next = isActive ? 'DISABLED' : 'ACTIVE'
  try {
    await ElMessageBox.confirm(
      isActive
        ? `确认停用账号「${row.username}」吗？停用后该账号无法登录。`
        : `确认恢复账号「${row.username}」吗？恢复后该账号可以重新登录。`,
      isActive ? '停用账号' : '恢复账号',
      { type: 'warning', confirmButtonText: isActive ? '确认停用' : '确认恢复', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  updatingId.value = row.id
  try {
    await adminApi.updateUserStatus(row.id, next)
    ElMessage.success(next === 'DISABLED' ? '账号已停用' : '账号已恢复')
    await load()
  } catch (cause) {
    if (cause.status === 409) ElMessage.warning(cause.message || '当前状态不允许该操作')
    else ElMessage.error(cause.message || '状态更新失败')
  } finally {
    updatingId.value = null
  }
}

/**
 * 员工账号停用/恢复。与用户账号同口径：两边都确认，取消不发请求。
 */
async function toggleStaffStatus(row) {
  if (updatingId.value) return
  const isActive = row.status === 'ACTIVE'
  const next = isActive ? 'DISABLED' : 'ACTIVE'
  try {
    await ElMessageBox.confirm(
      isActive
        ? `确认停用员工「${row.realName}」吗？停用后该账号无法登录。`
        : `确认恢复员工「${row.realName}」吗？恢复后该账号可以重新登录。`,
      isActive ? '停用员工账号' : '恢复员工账号',
      { type: 'warning', confirmButtonText: isActive ? '确认停用' : '确认恢复', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  updatingId.value = row.id
  try {
    await adminApi.updateStaffStatus(row.id, next)
    ElMessage.success(next === 'DISABLED' ? '员工账号已停用' : '员工账号已恢复')
    await load()
  } catch (cause) {
    if (cause.status === 409) ElMessage.warning(cause.message || '当前状态不允许该操作')
    else ElMessage.error(cause.message || '状态更新失败')
  } finally {
    updatingId.value = null
  }
}

watch(() => props.mode, () => {
  query.page = 1
  query.keyword = ''
  query.status = ''
  load()
})

onMounted(load)
</script>

<template>
  <div class="admin-users-page">
    <div class="admin-page-head">
      <div>
        <h2>{{ isUserMode ? '注册用户账户管理' : '内部员工权限管理' }}</h2>
        <p>
          {{ isUserMode
            ? '查看注册游客基础资料与账户状态，支持按用户名/昵称/手机号搜索、查看详情、禁用或恢复账号。'
            : '管理员创建与维护内部运营、客服人员账号；创建后角色由系统固定为 STAFF，本页不提供角色选择。' }}
        </p>
      </div>
      <button v-if="isStaffMode" class="primary-button" @click="openCreateStaff">
        + 新增员工
      </button>
    </div>

    <div class="admin-panel">
      <form class="toolbar" @submit.prevent="search">
        <input
          v-model="query.keyword"
          class="search-input"
          :placeholder="isUserMode
            ? '按用户名、昵称或手机号搜索'
            : '按姓名、工号或手机号搜索'"
        />
        <select v-model="query.status" class="filter-select">
          <option value="">全部状态</option>
          <option value="ACTIVE">{{ isUserMode ? '正常使用' : '在岗' }}</option>
          <option value="DISABLED">{{ isUserMode ? '已冻结' : '已停用' }}</option>
        </select>
        <button type="submit" class="secondary-button" :disabled="loading">查询</button>
        <button
          v-if="query.keyword || query.status"
          type="button"
          class="text-button"
          :disabled="loading"
          @click="resetSearch"
        >清空</button>
      </form>

      <RequestState
        :loading="loading"
        :error="error"
        :empty="!loading && !error && rows.length === 0"
        :empty-text="isUserMode ? '暂无符合条件的用户。' : '暂无符合条件的员工。'"
        @retry="load"
      >
        <table class="data-table">
          <thead>
            <tr v-if="isStaffMode">
              <th>姓名</th>
              <th>登录账号</th>
              <th>员工工号</th>
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
            <template v-if="isStaffMode">
              <tr v-for="row in rows" :key="row.id">
                <td><strong>{{ row.realName }}</strong></td>
                <td>{{ row.username }}</td>
                <td>{{ row.employeeNo }}</td>
                <td>{{ row.department || '—' }}</td>
                <td>{{ row.position || '—' }}</td>
                <td>{{ row.phone || '—' }}</td>
                <td>
                  <span class="tag" :class="statusClass(row.status)">
                    {{ staffStatusLabel(row.status) }}
                  </span>
                </td>
                <td style="text-align: right;" class="row-actions">
                  <button
                    type="button"
                    class="text-button"
                    @click="openEditStaff(row)"
                  >编辑</button>
                  <span class="divider">|</span>
                  <button
                    type="button"
                    class="text-button"
                    :class="row.status === 'ACTIVE' ? 'text-danger' : 'text-success'"
                    :disabled="updatingId === row.id"
                    @click="toggleStaffStatus(row)"
                  >
                    {{ updatingId === row.id
                      ? '提交中…'
                      : row.status === 'ACTIVE' ? '停用' : '恢复' }}
                  </button>
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
                  <span class="tag" :class="statusClass(row.status)">
                    {{ statusLabel(row.status) }}
                  </span>
                </td>
                <td>{{ row.createdAt }}</td>
                <td style="text-align: right;" class="row-actions">
                  <button
                    type="button"
                    class="text-button"
                    :disabled="detailLoadingId === row.id"
                    @click="openDetail(row)"
                  >
                    {{ detailLoadingId === row.id ? '加载中…' : '查看' }}
                  </button>
                  <span class="divider">|</span>
                  <button
                    type="button"
                    class="text-button"
                    :class="row.status === 'ACTIVE' ? 'text-danger' : 'text-success'"
                    :disabled="updatingId === row.id"
                    @click="toggleUserStatus(row)"
                  >
                    {{ updatingId === row.id
                      ? '提交中…'
                      : row.status === 'ACTIVE' ? '停用' : '恢复' }}
                  </button>
                </td>
              </tr>
            </template>
          </tbody>
        </table>

        <div class="resource-pager">
          <button type="button" class="secondary-button" :disabled="loading || query.page <= 1" @click="goToPage(-1)">上一页</button>
          <span class="muted-text">第 {{ query.page }} / {{ totalPages || 1 }} 页，共 {{ total }} 条</span>
          <button type="button" class="secondary-button" :disabled="loading || query.page >= totalPages" @click="goToPage(1)">下一页</button>
        </div>
      </RequestState>
    </div>

    <!-- 用户详情弹窗（仅用户模式） -->
    <el-dialog
      v-if="isUserMode"
      v-model="detailVisible"
      title="用户资料"
      width="min(560px, calc(100vw - 32px))"
      @close="onDetailClose"
    >
      <div v-if="detailLoading"><el-skeleton :rows="5" animated /></div>

      <div v-else-if="detailError" class="request-state" role="alert">
        <strong>暂时无法加载</strong>
        <p>{{ detailError }}</p>
      </div>

      <div v-else-if="detail" class="user-detail">
        <dl class="detail-list">
          <div><dt>用户 ID</dt><dd>#{{ detail.id }}</dd></div>
          <div><dt>用户名</dt><dd>{{ detail.username }}</dd></div>
          <div><dt>昵称</dt><dd>{{ detail.nickname || '—' }}</dd></div>
          <div><dt>真实姓名</dt><dd>{{ detail.realName || '—' }}</dd></div>
          <div><dt>手机号</dt><dd>{{ detail.phone || '—' }}</dd></div>
          <div><dt>邮箱</dt><dd>{{ detail.email || '—' }}</dd></div>
          <div>
            <dt>账号状态</dt>
            <dd>
              <span class="tag" :class="statusClass(detail.status)">{{ statusLabel(detail.status) }}</span>
            </dd>
          </div>
          <div>
            <dt>角色</dt>
            <dd>{{ (detail.roles || []).join(' · ') || '—' }}</dd>
          </div>
          <div><dt>注册时间</dt><dd>{{ detail.createdAt }}</dd></div>
        </dl>
        <p class="detail-hint">敏感字段（密码、完整证件号）不在本页面展示。</p>
      </div>
    </el-dialog>

    <!-- 员工表单弹窗（仅员工模式） -->
    <StaffFormDialog
      v-if="isStaffMode"
      v-model="staffDialogVisible"
      :staff="editingStaff"
      @saved="onStaffSaved"
    />
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  gap: 10px;
  margin-bottom: 20px;
}

.search-input {
  flex: 1;
  min-width: 220px;
  max-width: 420px;
  padding: 7px 10px;
  border: 1px solid var(--border-strong);
  border-radius: 8px;
  background: #fff;
  font-size: 13px;
  color: var(--text-primary);
}

.divider { color: var(--border-strong); margin: 0 6px; font-size: 11px; }
.row-actions { white-space: nowrap; }
.text-danger { color: var(--danger-red) !important; }
.text-success { color: var(--success-text) !important; }
.muted-text { color: var(--text-tertiary); font-size: 12px; }

.resource-pager {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 12px;
}

.user-detail .detail-list {
  display: grid;
  gap: 10px;
  margin: 0 0 12px;
}

.user-detail .detail-list > div {
  display: grid;
  grid-template-columns: 100px 1fr;
  gap: 8px;
}

.user-detail dt {
  font-size: 12px;
  color: var(--text-tertiary);
}

.user-detail dd {
  margin: 0;
  font-size: 13px;
  color: var(--text-primary);
}

.detail-hint {
  margin: 8px 0 0;
  font-size: 12px;
  color: var(--text-tertiary);
}
</style>