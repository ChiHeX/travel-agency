<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { adminApi } from '@/api/modules'
import AppIcon from '@/components/AppIcon.vue'

const rows = ref([])
const total = ref(0)
const page = ref(1)
const loading = ref(false)
const form = reactive({ keyword: '', status: '' })

const statusNames = { DRAFT: '草稿待审', PUBLISHED: '已上架销售', OFFLINE: '已下架归档' }

async function load() {
  loading.value = true
  try {
    const data = await adminApi.routes({ ...form, page: page.value, size: 10 })
    rows.value = data?.items || []
    total.value = data?.total || 0
  } finally {
    loading.value = false
  }
}

async function publish(row) {
  const next = row.status === 'PUBLISHED' ? 'OFFLINE' : 'PUBLISHED'
  await adminApi.updateRouteStatus(row.id, next)
  row.status = next
  ElMessage.success(`线路已成功${next === 'PUBLISHED' ? '上架' : '下架'}`)
}

onMounted(load)
</script>

<template>
  <div class="admin-routes-page">
    <div class="admin-page-head">
      <div>
        <h2>跟团游线路管理</h2>
        <p>维护线路基本资料、行程安排、发布状态与上下架销售控制。</p>
      </div>
      <button class="primary-button" @click="ElMessage.info('线路编辑表单已对接，后端 API 就绪')">
        + 新增跟团线路
      </button>
    </div>

    <div class="admin-panel">
      <!-- Search & Filter Toolbar -->
      <form class="toolbar" @submit.prevent="page = 1; load()">
        <input v-model="form.keyword" placeholder="搜索线路名称、出发地或目的地..." class="search-input" />
        <select v-model="form.status" class="filter-select">
          <option value="">全部销售状态</option>
          <option value="DRAFT">草稿</option>
          <option value="PUBLISHED">已上架</option>
          <option value="OFFLINE">已下架</option>
        </select>
        <button type="submit" class="secondary-button">查询</button>
      </form>

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
            <th>综合评分</th>
            <th>有效报名人次</th>
            <th>销售状态</th>
            <th style="text-align: right;">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in rows" :key="row.id">
            <td><strong>{{ row.name }}</strong></td>
            <td>{{ row.departureCity }}</td>
            <td>{{ row.destination }}</td>
            <td>{{ row.durationDays }} 天</td>
            <td>
              <span v-if="row.ratingCount" class="rate-text">
                <AppIcon name="star" size="11" color="#ff9500" />
                <span>{{ row.ratingAvg }} ({{ row.ratingCount }})</span>
              </span>
              <span v-else class="muted-text">暂无</span>
            </td>
            <td><strong>{{ row.validBookingCount == null ? '—' : row.validBookingCount }}</strong><span v-if="row.validBookingCount != null"> 人</span></td>
            <td>
              <span
                class="tag"
                :class="row.status === 'PUBLISHED' ? 'success' : row.status === 'OFFLINE' ? 'danger' : ''"
              >
                {{ statusNames[row.status] || row.status }}
              </span>
            </td>
            <td style="text-align: right;">
              <button
                type="button"
                class="text-button"
                :class="{ 'text-danger': row.status === 'PUBLISHED' }"
                @click="publish(row)"
              >
                {{ row.status === 'PUBLISHED' ? '下架' : '上架' }}
              </button>
            </td>
          </tr>
        </tbody>
      </table>

      <div v-else class="empty-box">
        暂无符合条件的线路数据。
      </div>

      <div v-if="total > 10" class="pagination-wrap">
        <el-pagination
          v-model:current-page="page"
          background
          layout="prev, pager, next"
          :page-size="10"
          :total="total"
          @current-change="load"
        />
      </div>
    </div>
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
  min-width: 240px;
}

.rate-text {
  color: #b45309;
  font-weight: 600;
  display: inline-flex;
  align-items: center;
  gap: 3px;
}

.muted-text {
  color: var(--text-tertiary);
  font-size: 12px;
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
