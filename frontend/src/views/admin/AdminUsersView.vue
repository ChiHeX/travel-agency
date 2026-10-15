<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { adminApi } from '@/api/modules'
import RequestState from '@/components/RequestState.vue'
import { codePointLength } from '@/utils/text'

const props = defineProps({ mode: { type: String, default: 'users' } })

const rows = ref([])
const loading = ref(false)
const error = ref('')
/** 正在提交状态变更的用户 id。 */
const updatingId = ref(null)
/** 正在加载详情的用户 id（用于禁用详情按钮）。 */
const detailLoadingId = ref(null)

const query = reactive({ page: 1, size: 20, keyword: '', status: '' })
const total = ref(0)
const totalPages = ref(0)

const KEYWORD_MAX = 100

// ---------- 详情弹窗 ----------
const detailVisible = ref(false)
const detail = ref(null)
const detailError = ref('')
const detailLoading = ref(false)

const STATUS_LABEL = { ACTIVE: '正常使用', DISABLED: '已冻结' }
const statusLabel = (status) => STATUS_LABEL[status] || status
const statusClass = (status) => (status === 'ACTIVE' ? 'success' : 'danger')

const isUserMode = computed(() => props.mode === 'users')

async function load(retryOnEmptyPage = true) {
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
      // 员工模式暂保持原样（E-02 单独做）
      result = await adminApi.staff({ page: query.page, size: query.size })
    }
    rows.value = result?.items || []
    total.value = Number(result?.total ?? rows.value.length)
    totalPages.value = Number(result?.totalPages ?? (rows.value.length ? 1 : 0))
    // 筛选后当前页可能空了：回退一页
    if (retryOnEmptyPage && !rows.value.length && query.page > 1) {
      query.page -= 1
      await load(false)
    }
  } catch (cause) {
    rows.value = []
    error.value = cause.message || '账号列表加载失败'
  } finally {
    loading.value = false
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

// ---------- 详情 ----------
async function openDetail(row) {
  detailVisible.value = true
  detailLoading.value = true
  detailLoadingId.value = row.id
  detailError.value = ''
  detail.value = null
  try {
    detail.value = await adminApi.user(row.id)
  } catch (cause) {
    detailError.value = cause.message || '用户资料加载失败'
  } finally {
    detailLoading.value = false
    detailLoadingId.value = null
  }
}

// ---------- 启用/停用 ----------
async function toggleStatus(row) {
  if (updatingId.value) return
  const isActive = row.status === 'ACTIVE'
  const next = isActive ? 'DISABLED' : 'ACTIVE'
  if (isActive) {
    try {
      await ElMessageBox.confirm(
        `确认停用账号「${row.username}」吗？停用后该账号无法登录。`,
        '停用账号',
        { type: 'warning', confirmButtonText: '确认停用', cancelButtonText: '取消' }
      )
    } catch {
      return
    }
  }
  updatingId.value = row.id
  try {
    const updated = await adminApi.updateUserStatus(row.id, next)
    if (updated) Object.assign(row, updated)
    ElMessage.success(next === 'DISABLED' ? '账号已停用' : '账号已恢复')
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
            : '管理员负责内部运营、客服与导游账号角色分配。' }}
        </p>
      </div>
    </div>

    <div class="admin-panel">
      <!-- 搜索：仅用户模式；员工模式等到 E-02 再加 -->
      <form v-if="isUserMode" class="toolbar" @submit.prevent="search">
        <input
          v-model="query.keyword"
          class="search-input"
          placeholder="按用户名、昵称或手机号搜索"
        />
        <select v-model="query.status" class="filter-select">
          <option value="">全部状态</option>
          <option value="ACTIVE">正常使用</option>
          <option value="DISABLED">已冻结</option>
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
        :empty-text="isUserMode ? '暂无符合条件的用户。' : '暂无员工数据。'"
        @retry="load"
      >
        <table class="data-table">
          <thead>
            <tr v-if="!isUserMode">
              <th>员工工号</th>
              <th>用户账号 ID</th>
              <th>所属部门</th>
              <th>职级岗位</th>
              <th>入职/创建时间</th>
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
            <template v-if="!isUserMode">
              <tr v-for="row in rows" :key="row.id">
                <td><strong>{{ row.employeeNo }}</strong></td>
                <td>{{ row.userId }}</td>
                <td>{{ row.department || '—' }}</td>
                <td>{{ row.position || '—' }}</td>
                <td>{{ row.createdAt }}</td>
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
                    @click="toggleStatus(row)"
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

    <!-- 用户详情弹窗 -->
    <el-dialog v-model="detailVisible" title="用户资料" width="min(560px, calc(100vw - 32px))">
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