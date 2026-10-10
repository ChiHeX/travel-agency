<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { adminApi } from '@/api/modules'
import RequestState from '@/components/RequestState.vue'

const rows = ref([])
const total = ref(0)
const loading = ref(false)
const error = ref('')

const query = reactive({ page: 1, size: 20, module: '' })

const resultNames = { SUCCESS: '成功', FAILURE: '失败' }
const resultClasses = { SUCCESS: 'success', FAILURE: 'danger' }

const isEmpty = computed(() => !loading.value && !error.value && rows.value.length === 0)

async function load() {
  loading.value = true
  error.value = ''
  try {
    const data = await adminApi.logs({
      page: query.page,
      size: query.size,
      module: query.module || undefined
    })
    rows.value = data?.items || []
    total.value = data?.total || 0
  } catch (cause) {
    error.value = cause.message || '操作日志加载失败'
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

onMounted(load)
</script>

<template>
  <div class="admin-logs-page">
    <div class="admin-page-head">
      <div>
        <h2>操作日志</h2>
        <p>记录后台重要操作，用于审计与问题追溯。</p>
      </div>
    </div>

    <div class="admin-panel">
      <form class="toolbar" @submit.prevent="search">
        <input
          v-model="query.module"
          class="search-input"
          placeholder="按模块筛选，如 ORDER、ROUTE、REFUND..."
          maxlength="64"
        />
        <button type="submit" class="secondary-button">查询</button>
      </form>

      <RequestState
        :loading="loading"
        :error="error"
        :empty="isEmpty"
        empty-text="暂无操作日志。"
        @retry="load"
      >
        <table class="data-table">
          <thead>
            <tr>
              <th>日志 ID</th>
              <th>操作人</th>
              <th>模块</th>
              <th>操作类型</th>
              <th>对象</th>
              <th>结果</th>
              <th>详情</th>
              <th>IP</th>
              <th>时间</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in rows" :key="row.id">
              <td>#{{ row.id }}</td>
              <td>{{ row.operatorName }}</td>
              <td>{{ row.module }}</td>
              <td>{{ row.operationType }}</td>
              <td>
                <span v-if="row.objectType || row.objectId">
                  {{ row.objectType || '—' }} / {{ row.objectId || '—' }}
                </span>
                <span v-else>—</span>
              </td>
              <td>
                <span class="tag" :class="resultClasses[row.result] || ''">
                  {{ resultNames[row.result] || row.result }}
                </span>
              </td>
              <td class="detail-cell">{{ row.detail || '—' }}</td>
              <td>{{ row.ipAddress || '—' }}</td>
              <td>{{ row.createdAt }}</td>
            </tr>
          </tbody>
        </table>

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

.search-input {
  flex: 1;
  min-width: 220px;
}

.detail-cell {
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