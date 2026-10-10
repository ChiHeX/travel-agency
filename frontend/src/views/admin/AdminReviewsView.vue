<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { adminApi } from '@/api/modules'
import RequestState from '@/components/RequestState.vue'

const rows = ref([])
const total = ref(0)
const loading = ref(false)
const error = ref('')
const updating = ref(null)

const query = reactive({
  page: 1,
  size: 20,
  status: ''
})

const statusNames = { VISIBLE: '可见', HIDDEN: '已隐藏' }
const statusClasses = { VISIBLE: 'success', HIDDEN: 'danger' }

async function load() {
  loading.value = true
  error.value = ''
  try {
    const data = await adminApi.reviews({
      page: query.page,
      size: query.size,
      status: query.status || undefined
    })
    rows.value = data?.items || []
    total.value = data?.total || 0
  } catch (cause) {
    error.value = cause.message || '评价列表加载失败'
    rows.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function search() {
  query.page = 1
  load()
}

async function toggleStatus(row) {
  if (updating.value) return
  const next = row.status === 'VISIBLE' ? 'HIDDEN' : 'VISIBLE'
  updating.value = row.id
  try {
    const updated = await adminApi.updateReviewStatus(row.id, next)
    row.status = updated?.status || next
    ElMessage.success(next === 'HIDDEN' ? '评价已隐藏' : '评价已恢复可见')
  } catch (cause) {
    ElMessage.error(cause.message || '状态更新失败')
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
        <p>查看游客对已完成订单的评价，调整评价的可见状态。</p>
      </div>
    </div>

    <div class="admin-panel">
      <form class="toolbar" @submit.prevent="search">
        <select v-model="query.status" class="filter-select">
          <option value="">全部状态</option>
          <option value="VISIBLE">可见</option>
          <option value="HIDDEN">已隐藏</option>
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
              <td class="content-cell">{{ row.content }}</td>
              <td>
                <span class="tag" :class="statusClasses[row.status] || ''">
                  {{ statusNames[row.status] || row.status }}
                </span>
              </td>
              <td>{{ row.createdAt }}</td>
              <td style="text-align: right;">
                <button
                  type="button"
                  class="text-button"
                  :disabled="updating === row.id"
                  @click="toggleStatus(row)"
                >
                  {{ row.status === 'VISIBLE' ? '隐藏' : '恢复可见' }}
                </button>
              </td>
            </tr>
          </tbody>
        </table>

        <div v-else class="empty-box">暂无评价数据。</div>

        <div v-if="total > query.size" class="pagination-wrap">
          <el-pagination
            v-model:current-page="query.page"
            background
            layout="prev, pager, next"
            :page-size="query.size"
            :total="total"
            @current-change="load"
          />
        </div>
      </template>
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
  max-width: 320px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.pagination-wrap {
  display: flex;
  justify-content: center;
  margin-top: 24px;
}
</style>