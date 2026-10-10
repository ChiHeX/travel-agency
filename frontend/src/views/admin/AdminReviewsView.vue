<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { adminApi } from '@/api/modules'
import RequestState from '@/components/RequestState.vue'

const rows = ref([])
const loading = ref(false)
const error = ref('')
/** 正在提交状态变更的评价 id。 */
const updating = ref(null)

const query = reactive({ page: 1, size: 20, status: '' })

const STATUS_LABEL = { VISIBLE: '可见', HIDDEN: '已隐藏' }
const statusLabel = (status) => STATUS_LABEL[status] || status
const statusClass = (status) => (status === 'VISIBLE' ? 'success' : 'danger')

const page = computed(() => query.page)
const total = ref(0)
const totalPages = ref(0)

async function load(retryOnEmptyPage = true) {
  loading.value = true
  error.value = ''
  try {
    const result = await adminApi.reviews({
      page: query.page,
      size: query.size,
      status: query.status || undefined
    })
    rows.value = result?.items || []
    total.value = Number(result?.total ?? rows.value.length)
    totalPages.value = Number(result?.totalPages ?? (rows.value.length ? 1 : 0))
    if (retryOnEmptyPage && !rows.value.length && query.page > 1) {
      query.page -= 1
      await load(false)
    }
  } catch (cause) {
    rows.value = []
    error.value = cause.message || '评价列表加载失败'
  } finally {
    loading.value = false
  }
}

function search() {
  query.page = 1
  load()
}

function goToPage(delta) {
  const next = query.page + delta
  if (next < 1 || (totalPages.value && next > totalPages.value)) return
  query.page = next
  load()
}

/**
 * 调整评价可见状态，走契约 PATCH /admin/reviews/{reviewId}/status。
 * 乐观更新 + 失败回滚，错误提示由 axios 拦截器统一弹出。
 */
async function toggleStatus(row) {
  if (updating.value) return
  const next = row.status === 'VISIBLE' ? 'HIDDEN' : 'VISIBLE'
  const previous = row.status
  updating.value = row.id
  row.status = next
  try {
    const updated = await adminApi.updateReviewStatus(row.id, next)
    if (updated) Object.assign(row, updated)
    ElMessage.success(next === 'HIDDEN' ? '评价已隐藏' : '评价已恢复可见')
  } catch {
    row.status = previous
  } finally {
    updating.value = null
  }
}

onMounted(load)
</script>

<template>
  <div class="admin-reviews-page">
    <div class="admin-page-head">
      <div>
        <h2>评价管理</h2>
        <p>查看游客对已完成订单的评价，调整评价的可见状态；隐藏后不再出现在用户端。</p>
      </div>
    </div>

    <div class="admin-panel">
      <form class="toolbar" @submit.prevent="search">
        <select v-model="query.status" class="filter-select">
          <option value="">全部状态</option>
          <option value="VISIBLE">可见</option>
          <option value="HIDDEN">已隐藏</option>
        </select>
        <button type="submit" class="secondary-button" :disabled="loading">查询</button>
      </form>

      <RequestState
        :loading="loading"
        :error="error"
        :empty="!loading && !error && rows.length === 0"
        empty-text="暂无评价数据。"
        @retry="load"
      >
        <table class="data-table">
          <thead>
            <tr>
              <th>评价 ID</th>
              <th>订单号</th>
              <th>线路 ID</th>
              <th>用户</th>
              <th>评分</th>
              <th>内容</th>
              <th>状态</th>
              <th>评价时间</th>
              <th style="text-align: right;">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in rows" :key="row.id">
              <td>#{{ row.id }}</td>
              <td>{{ row.orderNo }}</td>
              <td>{{ row.routeId }}</td>
              <td>{{ row.userNickname }}</td>
              <td>{{ row.rating }} 分</td>
              <td class="content-cell">
                <el-tooltip
                  :content="row.content"
                  placement="top"
                  :show-after="300"
                  :disabled="!row.content || row.content.length <= 30"
                >
                  <span class="content-text">{{ row.content }}</span>
                </el-tooltip>
              </td>
              <td>
                <span class="tag" :class="statusClass(row.status)">{{ statusLabel(row.status) }}</span>
              </td>
              <td>{{ row.createdAt }}</td>
              <td style="text-align: right;">
                <button
                  type="button"
                  class="text-button"
                  :class="row.status === 'VISIBLE' ? 'text-danger' : 'text-success'"
                  :disabled="updating === row.id"
                  @click="toggleStatus(row)"
                >
                  {{ updating === row.id
                    ? '提交中…'
                    : row.status === 'VISIBLE' ? '隐藏' : '恢复可见' }}
                </button>
              </td>
            </tr>
          </tbody>
        </table>

        <div class="resource-pager">
          <button type="button" class="secondary-button" :disabled="loading || query.page <= 1" @click="goToPage(-1)">上一页</button>
          <span class="muted-text">第 {{ query.page }} / {{ totalPages || 1 }} 页，共 {{ total }} 条</span>
          <button type="button" class="secondary-button" :disabled="loading || query.page >= totalPages" @click="goToPage(1)">下一页</button>
        </div>
      </RequestState>
    </div>
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  gap: 10px;
  margin-bottom: 20px;
}

.content-cell {
  max-width: 360px;
}

.content-text {
  display: block;
  max-width: 360px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  cursor: help;
}

.resource-pager {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 12px;
}

.text-success { color: var(--success-text) !important; }
.text-danger { color: var(--danger-red) !important; }
.muted-text { color: var(--text-tertiary); font-size: 12px; }
</style>