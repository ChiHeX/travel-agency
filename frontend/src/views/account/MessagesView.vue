<script setup>
import { inject, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { accountApi } from '@/api/modules'
import RequestState from '@/components/RequestState.vue'

const messages = ref([])
const loading = ref(false)
const error = ref('')
const page = ref(1)
const total = ref(0)
const unreadOnly = ref(false)
const pending = ref(false)
const refreshUnread = inject('refreshUnread', () => {})

async function load() {
  loading.value = true
  error.value = ''
  try {
    const data = await accountApi.messages({ page: page.value, size: 10, unreadOnly: unreadOnly.value })
    messages.value = data.items
    total.value = data.total
  } catch (cause) {
    error.value = cause.message || '消息加载失败'
  } finally {
    loading.value = false
  }
}

async function read(item) {
  if (item.read || pending.value) return
  pending.value = true
  try {
    Object.assign(item, await accountApi.readMessage(item.id))
    await refreshUnread()
    if (unreadOnly.value) await load()
  } catch (cause) { error.value = cause.message || '标记失败' }
  finally { pending.value = false }
}

async function readAll() {
  if (pending.value) return
  pending.value = true
  try {
    await accountApi.readAllMessages()
    await refreshUnread()
    await load()
    ElMessage.success('已全部标为已读')
  } catch (cause) { error.value = cause.message || '标记失败' }
  finally { pending.value = false }
}
function changePage(value) { page.value = value; load() }

onMounted(load)
</script>

<template>
  <div class="account-page account-settings-page">
    <main class="account-content">
      <header class="account-page-heading">
        <div>
          <h1>消息</h1>
          <p>出团通知、支付状态、订单确认与售后提醒都会实时同步在这里。</p>
        </div>
      </header>

      <div class="message-toolbar">
        <el-checkbox v-model="unreadOnly" :disabled="loading" @change="changePage(1)">只看未读</el-checkbox>
        <button class="secondary-button" :disabled="pending || loading" @click="readAll">{{ pending ? '处理中…' : '全部已读' }}</button>
      </div>

      <RequestState v-if="error" :error="error" @retry="load" />
      <div v-else-if="loading" class="admin-panel">
        <el-skeleton :rows="6" animated />
      </div>

      <div v-else-if="messages.length" class="messages-list">
        <article
          v-for="item in messages"
          :key="item.id"
          class="message-card-item"
          :class="{ unread: !item.read }"
        >
          <div class="msg-status-indicator">
            <span class="dot-dot" :class="{ unread: !item.read }"></span>
          </div>

          <div class="msg-content-wrap">
            <div class="msg-head-row">
              <h3 :class="{ unread: !item.read }">{{ item.title }}</h3>
              <span class="msg-time">{{ item.createdAt }}</span>
            </div>
            <p class="msg-body-text">{{ item.content }}</p>
            <button v-if="!item.read" class="text-button" :disabled="pending" @click="read(item)">标为已读</button>
          </div>
        </article>
      </div>
      <div v-else class="empty-box">
        暂无任何通知消息，当您的订单产生状态变更时将在此呈现。
      </div>
      <el-pagination v-if="!loading && !error && total > 10" class="account-pagination" layout="prev, pager, next" :pager-count="5" :current-page="page" :page-size="10" :total="total" @current-change="changePage" />
    </main>
  </div>
</template>

<style scoped>
.message-toolbar { display: flex; justify-content: space-between; gap: 12px; margin-bottom: 16px; }
.msg-head-row { flex-wrap: wrap; }
.msg-content-wrap { min-width: 0; overflow-wrap: anywhere; }
.messages-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.message-card-item {
  display: grid;
  grid-template-columns: 24px 1fr;
  gap: 14px;
  background: white;
  border: 1px solid var(--border-line);
  border-radius: var(--radius-lg);
  padding: 18px 20px;
  cursor: pointer;
  box-shadow: var(--shadow-xs);
  transition: all 0.15s ease;
}

.message-card-item:hover {
  border-color: var(--brand-blue);
  box-shadow: var(--shadow-sm);
}

.message-card-item.unread {
  border-left: 3px solid var(--brand-blue);
  background: #fbfdff;
}

.msg-status-indicator {
  padding-top: 4px;
}

.dot-dot {
  display: block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #cbd5e1;
}

.dot-dot.unread {
  background: var(--brand-blue);
  box-shadow: 0 0 0 3px var(--brand-blue-tint);
}

.msg-head-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  margin-bottom: 6px;
}

.msg-head-row h3 {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
  margin: 0;
}

.msg-head-row h3.unread {
  font-weight: 700;
  color: var(--brand-blue-dark);
}

.msg-time {
  font-size: 12px;
  color: var(--text-tertiary);
}

.msg-body-text {
  font-size: 13px;
  color: var(--text-secondary);
  line-height: 1.6;
  margin: 0;
}
</style>
