<script setup>
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { adminApi } from '@/api/modules'
import AppIcon from '@/components/AppIcon.vue'
import RequestState from '@/components/RequestState.vue'
import RouteFormDialog from '@/components/RouteFormDialog.vue'

const router = useRouter()

const rows = ref([])
const total = ref(0)
const page = ref(1)
const pageSize = 10
const loading = ref(false)
const error = ref('')
const statusUpdating = ref(null)
const dialogVisible = ref(false)
const editingRoute = ref(null)

const form = reactive({ keyword: '', status: '' })

const statusNames = { DRAFT: '草稿待上架', PUBLISHED: '已上架销售', OFFLINE: '已下架' }
const statusClasses = { DRAFT: 'warning', PUBLISHED: 'success', OFFLINE: 'danger' }

async function load() {
  loading.value = true
  error.value = ''
  try {
    const data = await adminApi.routes({
      keyword: form.keyword || undefined,
      status: form.status || undefined,
      page: page.value,
      size: pageSize
    })
    rows.value = data?.items || []
    total.value = data?.total || 0
  } catch (cause) {
    error.value = cause.message || '线路列表加载失败'
    rows.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function search() {
  page.value = 1
  load()
}

function openCreate() {
  editingRoute.value = null
  dialogVisible.value = true
}

/**
 * 打开编辑弹窗。
 *
 * 列表返回的是契约 RouteSummary，只包含概要字段；如果直接用它预填表单，
 * 简介 / 费用包含 / 报名须知等字段会因为“看起来为空”而在保存时被清空。
 * 因此先按契约读取线路详情，拿到完整的 Route 再编辑。
 */
async function openEdit(row) {
  try {
    const detail = await adminApi.route(row.id)
    editingRoute.value = detail?.route || row
  } catch (cause) {
    ElMessage.error(cause.message || '线路详情加载失败，暂不能编辑')
    return
  }
  dialogVisible.value = true
}

async function toggleStatus(row) {
  if (statusUpdating.value) return
  const next = row.status === 'PUBLISHED' ? 'OFFLINE' : 'PUBLISHED'
  if (next === 'OFFLINE') {
    try {
      await ElMessageBox.confirm(
        `确认下架「${row.name}」吗？下架后游客端将不再展示该线路，已有团期与订单不受影响。`,
        '下架线路',
        { type: 'warning', confirmButtonText: '确认下架', cancelButtonText: '取消' }
      )
    } catch {
      return
    }
  }
  statusUpdating.value = row.id
  try {
    const updated = await adminApi.updateRouteStatus(row.id, next)
    row.status = updated?.status || next
    ElMessage.success(next === 'PUBLISHED' ? '线路已上架' : '线路已下架')
  } catch (cause) {
    // 例如：线路还没有任何行程时后端返回 409，这里把原因提示给用户。
    if (cause.status === 409) ElMessage.warning(cause.message || '线路当前状态不允许该操作')
    else if (cause.status !== 401) ElMessage.error(cause.message || '状态更新失败')
  } finally {
    statusUpdating.value = null
  }
}

function openDetail(row) {
  router.push({ name: 'admin-route-detail', params: { id: row.id } })
}

function money(value) {
  return value == null ? '—' : `¥${value}`
}

onMounted(load)
</script>

<template>
  <div class="admin-routes-page">
    <div class="admin-page-head">
      <div>
        <h2>跟团游线路管理</h2>
        <p>维护线路基本资料、每日行程与上下架状态；线路数据全部来自后端数据库。</p>
      </div>
      <button class="primary-button" @click="openCreate">+ 新增跟团线路</button>
    </div>

    <div class="admin-panel">
      <form class="toolbar" @submit.prevent="search">
        <input v-model="form.keyword" placeholder="搜索线路名称、目的地或出发城市..." class="search-input" />
        <select v-model="form.status" class="filter-select">
          <option value="">全部销售状态</option>
          <option value="DRAFT">草稿</option>
          <option value="PUBLISHED">已上架</option>
          <option value="OFFLINE">已下架</option>
        </select>
        <button type="submit" class="secondary-button">查询</button>
      </form>

      <RequestState v-if="error" :error="error" @retry="load" />

      <template v-else>
        <div v-if="loading">
          <el-skeleton :rows="8" animated />
        </div>

        <table v-else-if="rows.length" class="data-table">
          <thead>
            <tr>
              <th>线路名称</th>
              <th>出发城市</th>
              <th>目的地</th>
              <th>行程天数</th>
              <th>最低团期价</th>
              <th>最近出发</th>
              <th>可报名余位</th>
              <th>综合评分</th>
              <th>有效报名人次</th>
              <th>销售状态</th>
              <th style="text-align: right;">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in rows" :key="row.id">
              <td>
                <button type="button" class="text-button link-strong" @click="openDetail(row)">
                  {{ row.name }}
                </button>
              </td>
              <td>{{ row.departureCity }}</td>
              <td>{{ row.destination }}</td>
              <td>{{ row.durationDays }} 天</td>
              <td class="amount">{{ money(row.minAdultPrice) }}</td>
              <td>{{ row.nextDepartureDate || '暂无可售团期' }}</td>
              <td>{{ row.availableSeats == null ? '—' : `${row.availableSeats} 人` }}</td>
              <td>
                <span v-if="row.ratingCount" class="rate-text">
                  <AppIcon name="star" size="11" color="#ff9500" />
                  <span>{{ row.ratingAvg }} ({{ row.ratingCount }})</span>
                </span>
                <span v-else class="muted-text">暂无评价</span>
              </td>
              <td>{{ row.validBookingCount == null ? '—' : `${row.validBookingCount} 人` }}</td>
              <td>
                <span class="tag" :class="statusClasses[row.status] || ''">
                  {{ statusNames[row.status] || row.status }}
                </span>
              </td>
              <td style="text-align: right;" class="row-actions">
                <button type="button" class="text-button" @click="openDetail(row)">行程与详情</button>
                <span class="divider">|</span>
                <button type="button" class="text-button" @click="openEdit(row)">编辑</button>
                <span class="divider">|</span>
                <button
                  type="button"
                  class="text-button"
                  :class="{ 'text-danger': row.status === 'PUBLISHED' }"
                  :disabled="statusUpdating === row.id"
                  @click="toggleStatus(row)"
                >
                  {{ row.status === 'PUBLISHED' ? '下架' : '上架' }}
                </button>
              </td>
            </tr>
          </tbody>
        </table>

        <div v-else class="empty-box">
          暂无符合条件的线路数据。可以点击右上角“+ 新增跟团线路”创建第一条线路草稿。
        </div>

        <div v-if="total > pageSize" class="pagination-wrap">
          <el-pagination
            v-model:current-page="page"
            background
            layout="prev, pager, next"
            :page-size="pageSize"
            :total="total"
            @current-change="load"
          />
        </div>
      </template>
    </div>

    <RouteFormDialog v-model="dialogVisible" :route="editingRoute" @saved="load" />
  </div>
</template>

<style scoped>
.admin-page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 18px;
}

.admin-page-head h2 {
  margin: 0 0 4px;
  font-size: 18px;
}

.admin-page-head p {
  margin: 0;
  color: var(--text-secondary);
  font-size: 13px;
}

.toolbar {
  display: flex;
  gap: 10px;
  margin-bottom: 20px;
}

.search-input {
  flex: 1;
  min-width: 220px;
}

.rate-text {
  color: #b45309;
  font-weight: 600;
  display: inline-flex;
  align-items: center;
  gap: 3px;
}

.amount {
  color: var(--price-orange);
  font-weight: 700;
}

.muted-text {
  color: var(--text-tertiary);
  font-size: 12px;
}

.link-strong {
  font-weight: 600;
  color: var(--text-primary);
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
  color: var(--status-red) !important;
}

.pagination-wrap {
  display: flex;
  justify-content: center;
  margin-top: 24px;
}
</style>
