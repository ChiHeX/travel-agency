<script setup>
import { onMounted, reactive, ref } from 'vue'
import { adminApi } from '@/api/modules'
import RequestState from '@/components/RequestState.vue'

const rows = ref([])
const loading = ref(false)
const error = ref('')

const query = reactive({ page: 1, size: 20, module: '' })

const total = ref(0)
const totalPages = ref(0)

const RESULT_LABEL = { SUCCESS: '成功', FAILURE: '失败' }
const resultClass = (result) => (result === 'SUCCESS' ? 'success' : 'danger')

async function load(retryOnEmptyPage = true) {
  loading.value = true
  error.value = ''
  try {
    const result = await adminApi.logs({
      page: query.page,
      size: query.size,
      module: query.module || undefined
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
    error.value = cause.message || '操作日志加载失败'
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

onMounted(load)
</script>

<template>
  <div class="admin-logs-page">
    <div class="admin-page-head">
      <div>
        <h2>操作日志</h2>
        <p>记录后台重要操作，用于审计与问题追溯；仅管理员可见。</p>
      </div>
    </div>

    <div class="admin-panel">
      <form class="toolbar" @submit.prevent="search">
        <input
          v-model="query.module"
          class="search-input"
          placeholder="按模块筛选，例如 ORDER、ROUTE、REFUND"
        />
        <button type="submit" class="secondary-button" :disabled="loading">查询</button>
      </form>

      <RequestState
        :loading="loading"
        :error="error"
        :empty="!loading && !error && rows.length === 0"
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
                <span class="tag" :class="resultClass(row.result)">{{ RESULT_LABEL[row.result] || row.result }}</span>
              </td>
              <td class="detail-cell">{{ row.detail || '—' }}</td>
              <td>{{ row.ipAddress || '—' }}</td>
              <td>{{ row.createdAt }}</td>
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

.detail-cell {
  max-width: 320px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.resource-pager {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 12px;
}

.muted-text { color: var(--text-tertiary); font-size: 12px; }
</style>