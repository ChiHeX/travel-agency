<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { accountApi } from '@/api/modules'

const messages = ref([])
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    messages.value = (await accountApi.messages())?.items || []
  } finally {
    loading.value = false
  }
}

async function read(item) {
  if (item.read) return
  await accountApi.readMessage(item.id)
  item.read = true
  ElMessage.success('已标记为已读')
}

onMounted(load)
</script>

<template>
  <div class="account-page">
    <div class="container narrow-container page-section">
      <div class="section-head">
        <div>
          <span class="eyebrow">NOTIFICATIONS</span>
          <h2>消息中心</h2>
          <p>出团通知、支付状态、订单确认与售后提醒都会实时同步在这里。</p>
        </div>
      </div>

      <div v-if="loading" class="admin-panel">
        <el-skeleton :rows="6" animated />
      </div>

      <div v-else-if="messages.length" class="messages-list">
        <article
          v-for="item in messages"
          :key="item.id"
          class="message-card-item"
          :class="{ unread: !item.read }"
          @click="read(item)"
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
          </div>
        </article>
      </div>

      <div v-else class="empty-box">
        暂无任何通知消息，当您的订单产生状态变更时将在此呈现。
      </div>
    </div>
  </div>
</template>

<style scoped>
.account-page {
  background: var(--bg-canvas);
  min-height: calc(100vh - 64px);
}

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
