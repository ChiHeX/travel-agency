<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { accountApi } from '@/api/modules'

const items = ref([])
const form = reactive({ title: '', content: '' })
const loading = ref(false)
const submitting = ref(false)

async function load() {
  loading.value = true
  try {
    items.value = (await accountApi.consultations())?.items || []
  } finally {
    loading.value = false
  }
}

async function submit() {
  if (!form.title.trim() || !form.content.trim()) return ElMessage.warning('请填写问题标题和详细内容')
  submitting.value = true
  try {
    await accountApi.createConsultation(form)
    Object.assign(form, { title: '', content: '' })
    ElMessage.success('咨询工单已提交，客服将尽快答复')
    load()
  } finally {
    submitting.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="account-page">
    <div class="container consultation-layout page-section">
      <!-- Left: Create Consultation Form -->
      <div class="consultation-form-col">
        <div class="section-head">
          <div>
            <span class="eyebrow">HELP &amp; INQUIRY</span>
            <h2>在线咨询客服</h2>
            <p>工单式咨询服务，行程顾问与技术人员将直接为您解答疑问。</p>
          </div>
        </div>

        <div class="admin-panel form-panel-box">
          <form class="consultation-form" @submit.prevent="submit">
            <div class="form-field">
              <label>咨询主题 / 问题概要</label>
              <input v-model="form.title" placeholder="例如：咨询集合地点或行程安排" required />
            </div>

            <div class="form-field">
              <label>详细描述</label>
              <textarea
                v-model="form.content"
                rows="5"
                placeholder="请详细描述您在预订、行程安排或费用方面的疑问..."
                required
              ></textarea>
            </div>

            <button type="submit" class="primary-button" :disabled="submitting">
              {{ submitting ? '正在提交...' : '提交咨询工单' }}
            </button>
          </form>
        </div>
      </div>

      <!-- Right: History List -->
      <div class="consultation-history-col">
        <div class="section-head compact-head">
          <div>
            <span class="eyebrow">HISTORY</span>
            <h2>历史咨询记录</h2>
          </div>
        </div>

        <div v-if="loading">
          <el-skeleton :rows="6" animated />
        </div>

        <div v-else-if="items.length" class="history-list">
          <article
            v-for="item in items"
            :key="item.id"
            class="history-card"
          >
            <div class="card-status-row">
              <span class="tag" :class="item.status === 'REPLIED' ? 'success' : 'warning'">
                {{ item.status === 'REPLIED' ? '已回复' : '处理中' }}
              </span>
              <span class="consult-time">{{ item.createdAt }}</span>
            </div>

            <h3 class="consult-title">{{ item.title }}</h3>
            <p class="consult-content">{{ item.content }}</p>

            <!-- Staff Replies -->
            <div v-if="item.replies && item.replies.length" class="replies-container">
              <div v-for="reply in item.replies" :key="reply.id" class="reply-item">
                <span class="reply-badge">客服答复：</span>
                <p>{{ reply.content }}</p>
              </div>
            </div>
          </article>
        </div>

        <div v-else class="empty-box">
          暂无历史咨询记录。
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.account-page {
  background: var(--bg-canvas);
  min-height: calc(100vh - 64px);
}

.consultation-layout {
  display: grid;
  grid-template-columns: 1.2fr 1fr;
  gap: 36px;
  align-items: flex-start;
}

.form-panel-box {
  padding: 24px;
}

.history-list {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.history-card {
  background: white;
  border: 1px solid var(--border-line);
  border-radius: var(--radius-lg);
  padding: 18px;
  box-shadow: var(--shadow-xs);
}

.card-status-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}

.consult-time {
  font-size: 11px;
  color: var(--text-tertiary);
}

.consult-title {
  font-size: 15px;
  font-weight: 700;
  color: var(--text-primary);
  margin: 0 0 6px;
}

.consult-content {
  font-size: 13px;
  color: var(--text-secondary);
  line-height: 1.6;
  margin: 0 0 12px;
}

.replies-container {
  border-top: 1px solid var(--border-line);
  padding-top: 10px;
}

.reply-item {
  background: var(--brand-blue-subtle);
  border-radius: var(--radius-sm);
  padding: 10px 12px;
  font-size: 12px;
  line-height: 1.5;
  color: var(--text-primary);
}

.reply-badge {
  font-weight: 700;
  color: var(--brand-blue-dark);
  display: inline-block;
  margin-right: 4px;
}

.reply-item p {
  display: inline;
  margin: 0;
}

@media (max-width: 800px) {
  .consultation-layout {
    grid-template-columns: 1fr;
  }
}
</style>
