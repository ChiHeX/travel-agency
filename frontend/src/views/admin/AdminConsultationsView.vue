<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { adminApi } from '@/api/modules'
import RequestState from '@/components/RequestState.vue'

const rows = ref([])
const total = ref(0)
const loading = ref(false)
const error = ref('')

const query = reactive({ page: 1, size: 20, status: '' })

const detailVisible = ref(false)
const detailLoading = ref(false)
const detailError = ref('')
const detail = ref(null)

const replyContent = ref('')
const replySaving = ref(false)
const replyError = ref('')

const closingId = ref(null)

const statusNames = { WAIT_REPLY: '待回复', REPLIED: '已回复', CLOSED: '已关闭' }
const statusClasses = { WAIT_REPLY: 'warning', REPLIED: 'success', CLOSED: 'danger' }

const isEmpty = computed(() => !loading.value && !error.value && rows.value.length === 0)

async function load() {
  loading.value = true
  error.value = ''
  try {
    const data = await adminApi.consultations({
      page: query.page,
      size: query.size,
      status: query.status || undefined
    })
    rows.value = data?.items || []
    total.value = data?.total || 0
  } catch (cause) {
    error.value = cause.message || '咨询列表加载失败'
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

async function openDetail(row) {
  detailVisible.value = true
  detailLoading.value = true
  detailError.value = ''
  detail.value = null
  replyContent.value = ''
  replyError.value = ''
  try {
    detail.value = await adminApi.consultation(row.id)
  } catch (cause) {
    detailError.value = cause.message || '咨询详情加载失败'
  } finally {
    detailLoading.value = false
  }
}

async function submitReply() {
  if (replySaving.value || !detail.value) return
  const content = replyContent.value.trim()
  if (!content) {
    replyError.value = '请填写回复内容'
    return
  }
  replySaving.value = true
  replyError.value = ''
  try {
    detail.value = await adminApi.replyConsultation(detail.value.id, { content })
    replyContent.value = ''
    ElMessage.success('回复已发送')
    await load()
  } catch (cause) {
    replyError.value = cause.message || '回复失败'
  } finally {
    replySaving.value = false
  }
}

async function closeConsultation(row) {
  if (closingId.value) return
  try {
    await ElMessageBox.confirm(`确认关闭咨询「${row.title}」吗？`, '关闭咨询', {
      type: 'warning',
      confirmButtonText: '确认关闭',
      cancelButtonText: '取消'
    })
  } catch {
    return
  }
  closingId.value = row.id
  try {
    await adminApi.closeConsultation(row.id)
    ElMessage.success('咨询已关闭')
    await load()
    if (detail.value && detail.value.id === row.id) {
      detail.value = await adminApi.consultation(row.id)
    }
  } catch (cause) {
    ElMessage.error(cause.message || '关闭失败')
  } finally {
    closingId.value = null
  }
}

onMounted(load)
</script>

<template>
  <div class="admin-consultations-page">
    <div class="admin-page-head">
      <div>
        <h2>在线咨询</h2>
        <p>查看游客提交的咨询，进行回复或关闭。</p>
      </div>
    </div>

    <div class="admin-panel">
      <form class="toolbar" @submit.prevent="search">
        <select v-model="query.status" class="filter-select">
          <option value="">全部状态</option>
          <option value="WAIT_REPLY">待回复</option>
          <option value="REPLIED">已回复</option>
          <option value="CLOSED">已关闭</option>
        </select>
        <button type="submit" class="secondary-button">查询</button>
      </form>

      <RequestState
        :loading="loading"
        :error="error"
        :empty="isEmpty"
        empty-text="暂无咨询数据。"
        @retry="load"
      >
        <table class="data-table">
          <thead>
            <tr>
              <th>咨询 ID</th>
              <th>用户</th>
              <th>标题</th>
              <th>状态</th>
              <th>提交时间</th>
              <th style="text-align: right;">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in rows" :key="row.id">
              <td>#{{ row.id }}</td>
              <td>{{ row.userNickname || '—' }}</td>
              <td>{{ row.title }}</td>
              <td>
                <span class="tag" :class="statusClasses[row.status] || ''">
                  {{ statusNames[row.status] || row.status }}
                </span>
              </td>
              <td>{{ row.createdAt }}</td>
              <td style="text-align: right;">
                <button type="button" class="text-button" @click="openDetail(row)">查看/回复</button>
                <span class="divider">|</span>
                <button
                  type="button"
                  class="text-button text-danger"
                  :disabled="closingId === row.id || row.status === 'CLOSED'"
                  @click="closeConsultation(row)"
                >
                  关闭
                </button>
              </td>
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

    <el-dialog v-model="detailVisible" title="咨询详情" width="min(680px, calc(100vw - 32px))">
      <div v-if="detailLoading"><el-skeleton :rows="4" animated /></div>

      <div v-else-if="detailError" class="request-state" role="alert">
        <strong>暂时无法加载</strong>
        <p>{{ detailError }}</p>
      </div>

      <div v-else-if="detail" class="consultation-detail">
        <div class="detail-header">
          <h3>{{ detail.title }}</h3>
          <span class="tag" :class="statusClasses[detail.status] || ''">
            {{ statusNames[detail.status] || detail.status }}
          </span>
        </div>
        <p class="detail-meta">用户：{{ detail.userNickname || '—' }} · 提交时间：{{ detail.createdAt }}</p>
        <div class="detail-content">{{ detail.content }}</div>

        <h4 class="reply-title">回复记录</h4>
        <div v-if="detail.replies && detail.replies.length" class="reply-list">
          <div v-for="reply in detail.replies" :key="reply.id" class="reply-item">
            <div class="reply-meta">{{ reply.staffName }} · {{ reply.createdAt }}</div>
            <div class="reply-content">{{ reply.content }}</div>
          </div>
        </div>
        <p v-else class="empty-inline">暂无回复。</p>

        <div v-if="detail.status !== 'CLOSED'" class="reply-form">
          <p v-if="replyError" class="form-error" role="alert">{{ replyError }}</p>
          <textarea
            v-model="replyContent"
            rows="3"
            maxlength="2000"
            placeholder="输入回复内容，2-2000 字"
          ></textarea>
          <button type="button" class="primary-button" :disabled="replySaving" @click="submitReply">
            {{ replySaving ? '发送中…' : '发送回复' }}
          </button>
        </div>
        <p v-else class="empty-inline">该咨询已关闭，无法继续回复。</p>
      </div>
    </el-dialog>
  </div>
</template>

<style scoped>
.toolbar { display: flex; gap: 10px; margin-bottom: 20px; }
.divider { color: var(--border-strong); margin: 0 6px; font-size: 11px; }
.text-danger { color: var(--danger-red) !important; }
.pagination-wrap { display: flex; justify-content: center; margin-top: 24px; }

.consultation-detail .detail-header { display: flex; align-items: center; gap: 10px; margin-bottom: 6px; }
.consultation-detail h3 { margin: 0; font-size: 16px; }
.detail-meta { margin: 0 0 12px; font-size: 12px; color: var(--text-tertiary); }
.detail-content { padding: 12px; background: var(--bg-subtle, #fafafa); border-radius: 8px; font-size: 13px; line-height: 1.6; white-space: pre-wrap; }
.reply-title { margin: 20px 0 10px; font-size: 14px; }
.reply-list { display: flex; flex-direction: column; gap: 10px; }
.reply-item { padding: 10px 12px; border-left: 3px solid var(--brand-blue, #2563eb); background: var(--bg-subtle, #fafafa); border-radius: 4px; }
.reply-meta { font-size: 12px; color: var(--text-tertiary); margin-bottom: 4px; }
.reply-content { font-size: 13px; line-height: 1.6; white-space: pre-wrap; }
.empty-inline { font-size: 12px; color: var(--text-tertiary); }
.reply-form { margin-top: 20px; display: flex; flex-direction: column; gap: 10px; }
.reply-form textarea { width: 100%; padding: 10px; border: 1px solid var(--border-divider, #e5e7eb); border-radius: 6px; font-family: inherit; font-size: 13px; resize: vertical; }
.form-error { margin: 0; color: var(--danger-red, #dc2626); font-size: 12px; }
</style>