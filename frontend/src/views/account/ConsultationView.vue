<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { accountApi } from '@/api/modules'
import RequestState from '@/components/RequestState.vue'
import { codePointLength } from '@/utils/text'

const items = ref([])
const form = reactive({ title: '', content: '' })
const loading = ref(false)
const submitting = ref(false)
const error = ref('')
const submitError = ref('')
const page = ref(1)
const total = ref(0)
const detailOpen = ref(false)
const selectedId = ref(null)
const detail = ref(null)
const detailLoading = ref(false)
const detailError = ref('')
const closing = ref(false)
const closeError = ref('')
const deleteError = ref('')
const deletingId = ref(null)
let detailRequest = 0

function formatTime(value) {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', hour12: false
  }).format(date)
}

async function openDetail(id) {
  const request = ++detailRequest
  selectedId.value = id
  detailOpen.value = true
  detail.value = null
  detailLoading.value = true
  detailError.value = ''
  closeError.value = ''
  try {
    const result = await accountApi.consultation(id)
    if (request === detailRequest) detail.value = result
  } catch (cause) {
    if (request === detailRequest) detailError.value = cause.message || '咨询详情加载失败'
  } finally {
    if (request === detailRequest) detailLoading.value = false
  }
}

async function closeConsultation() {
  if (closing.value || !detail.value || detail.value.status === 'CLOSED') return
  closing.value = true
  closeError.value = ''
  try {
    try {
      await ElMessageBox.confirm('确认问题已解决并关闭这条咨询吗？', '关闭咨询', {
        customClass: 'order-cancel-confirm',
        confirmButtonText: '确认关闭', cancelButtonText: '保留咨询', type: 'warning'
      })
    } catch { return }
    const updated = await accountApi.closeConsultation(detail.value.id)
    detail.value = updated
    items.value = items.value.map((item) => item.id === updated.id ? updated : item)
    ElMessage.success('咨询已关闭')
  } catch (cause) {
    closeError.value = cause.message || '关闭失败，请重试'
  } finally {
    closing.value = false
  }
}

async function deleteConsultation(consultation) {
  if (closing.value || consultation?.status !== 'CLOSED') return
  closing.value = true
  deletingId.value = consultation.id
  closeError.value = ''
  deleteError.value = ''
  try {
    try {
      await ElMessageBox.confirm('删除后，这条咨询及全部客服回复将无法恢复，是否继续？', '删除咨询', {
        customClass: 'order-cancel-confirm', type: 'warning',
        confirmButtonText: '确认删除', cancelButtonText: '保留咨询'
      })
    } catch { return }
    await accountApi.deleteConsultation(consultation.id)
    if (detail.value?.id === consultation.id) {
      detailOpen.value = false
      detail.value = null
    }
    total.value = Math.max(0, total.value - 1)
    page.value = Math.min(page.value, Math.max(1, Math.ceil(total.value / 10)))
    ElMessage.success('咨询已删除')
    await load()
  } catch (cause) {
    if (detailOpen.value && detail.value?.id === consultation.id) {
      closeError.value = cause.message || '删除失败，请重试'
    } else {
      deleteError.value = cause.message || '删除失败，请重试'
    }
  } finally {
    closing.value = false
  }
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const result = await accountApi.consultations({ page: page.value, size: 10 })
    items.value = result.items
    total.value = result.total
  } catch (cause) { error.value = cause.message || '咨询加载失败'
  } finally {
    loading.value = false
  }
}

async function submit() {
  if (submitting.value) return
  submitError.value = ''
  if (!form.title.trim() || !form.content.trim()) return ElMessage.warning('请填写问题标题和详细内容')
  // 契约 ConsultationRequest：title 2–100、content 2–2000。这里数的是字符（码点），与后端
  // @CodePointLength 同口径；用 String#length（UTF-16 码元）会把 emoji 记成 2 个字符而误判超长。
  // 校验的是即将提交的原值（不做 trim），否则去掉首尾空格后仍可能超过契约上限。
  if (codePointLength(form.title) < 2 || codePointLength(form.title) > 100) {
    return ElMessage.warning('咨询主题需为 2–100 个字符')
  }
  if (codePointLength(form.content) < 2 || codePointLength(form.content) > 2000) {
    return ElMessage.warning('详细描述需为 2–2000 个字符')
  }
  submitting.value = true
  try {
    await accountApi.createConsultation(form)
    Object.assign(form, { title: '', content: '' })
    ElMessage.success('咨询工单已提交，客服将尽快答复')
    load()
  } catch (cause) { submitError.value = cause.message || '提交失败，请重试'
  } finally {
    submitting.value = false
  }
}

function changePage(value) { page.value = value; load() }

onMounted(load)
</script>

<template>
  <div class="account-page account-settings-page">
    <main class="account-content">
      <header class="account-page-heading">
        <div>
          <h1>咨询</h1>
          <p>向客服提交问题，并查看历史答复。</p>
        </div>
      </header>

      <div class="consultation-layout">
        <div class="consultation-form-col">
          <div class="settings-heading"><h2>发起咨询</h2></div>

        <div class="admin-panel form-panel-box">
          <form class="consultation-form" @submit.prevent="submit">
            <p v-if="submitError" class="form-error" role="alert">{{ submitError }}</p>
            <!-- 去掉 maxlength：它按 UTF-16 码元（emoji 记 2）截断，会把契约允许的 emoji 文本静默砍短；上限改由提交时的码点校验负责 -->
            <div class="form-field">
              <label>咨询主题 / 问题概要</label>
              <input v-model="form.title" minlength="2" placeholder="例如：咨询集合地点或行程安排" required />
            </div>

            <div class="form-field">
              <label>详细描述</label>
              <textarea
                v-model="form.content"
                rows="5"
                minlength="2"
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

        <div class="consultation-history-col">
          <div class="settings-heading"><h2>历史记录</h2></div>
          <p v-if="deleteError" class="form-error" role="alert">{{ deleteError }}</p>

        <RequestState v-if="error" :error="error" @retry="load" />
        <div v-else-if="loading">
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
                {{ { REPLIED: '已回复', CLOSED: '已关闭', WAIT_REPLY: '等待回复' }[item.status] || item.status }}
              </span>
              <span class="consult-time">{{ item.createdAt }}</span>
            </div>

            <h3 class="consult-title">{{ item.title }}</h3>
            <p class="consult-content">{{ item.content }}</p>
            <div class="consult-card-actions">
              <button type="button" class="secondary-button consult-detail-button" :disabled="closing" @click="openDetail(item.id)">查看详情</button>
              <button v-if="item.status === 'CLOSED'" type="button" class="consult-delete-button" :disabled="closing" @click="deleteConsultation(item)">{{ closing && deletingId === item.id ? '删除中...' : '删除' }}</button>
            </div>

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
        <el-pagination v-if="!loading && !error && total > 10" class="account-pagination" layout="prev, pager, next" :pager-count="5" :current-page="page" :page-size="10" :total="total" @current-change="changePage" />
        </div>
      </div>
    </main>
    <el-dialog v-model="detailOpen" class="consultation-dialog" title="咨询详情" width="min(600px, calc(100vw - 32px))" align-center :close-on-click-modal="!closing" :close-on-press-escape="!closing" :show-close="!closing">
      <RequestState :loading="detailLoading" :error="detailError" @retry="openDetail(selectedId)">
        <article v-if="detail" class="consultation-detail">
          <header class="detail-heading">
            <span class="consult-status" :class="detail.status.toLowerCase()">{{ { REPLIED: '已回复', CLOSED: '已关闭', WAIT_REPLY: '等待回复' }[detail.status] || detail.status }}</span>
            <h3>{{ detail.title }}</h3>
            <p class="consult-time">提交于 {{ formatTime(detail.createdAt) }}</p>
          </header>
          <section class="detail-question" aria-label="问题描述">
            <h4>问题描述</h4>
            <p>{{ detail.content }}</p>
          </section>
          <section class="detail-responses" aria-label="客服回复">
            <div class="detail-section-heading"><h4>客服回复</h4><span>{{ detail.replies.length }} 条回复</span></div>
            <div v-if="detail.replies.length" class="detail-replies">
              <article v-for="reply in detail.replies" :key="reply.id" class="detail-reply">
                <div class="reply-avatar" aria-hidden="true">{{ reply.staffName?.slice(0, 1) || '客' }}</div>
                <div class="reply-body">
                  <div class="reply-meta"><strong>{{ reply.staffName }}</strong><time>{{ formatTime(reply.createdAt) }}</time></div>
                  <p>{{ reply.content }}</p>
                </div>
              </article>
            </div>
            <p v-else class="detail-empty">{{ detail.status === 'CLOSED' ? '这条咨询暂无客服回复。' : '咨询已收到，请耐心等待客服回复。' }}</p>
          </section>
        </article>
      </RequestState>
      <template #footer>
        <div class="detail-footer">
          <p v-if="closeError" class="form-error" role="alert">{{ closeError }}</p>
          <div class="detail-actions">
            <button v-if="detail && !detailLoading && !detailError && detail.status !== 'CLOSED'" type="button" class="close-consultation-button" :disabled="closing" @click="closeConsultation">{{ closing ? '正在关闭...' : '关闭咨询' }}</button>
            <button v-else-if="detail?.status === 'CLOSED' && !detailLoading && !detailError" type="button" class="close-consultation-button" :disabled="closing" @click="deleteConsultation(detail)">{{ closing ? '正在删除...' : '删除咨询' }}</button>
            <button type="button" class="secondary-button" :disabled="closing" @click="detailOpen = false">返回列表</button>
          </div>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.consult-detail-button { min-height: 32px; padding: 0 14px; font-size: 12px; border-radius: var(--account-radius-action); }
.consult-card-actions { display: flex; align-items: center; gap: 8px; }
.consult-delete-button { display: inline-flex; align-items: center; justify-content: center; min-height: 32px; padding: 0 14px; border: 1px solid var(--status-red); border-radius: var(--account-radius-action); background: #fff; color: var(--status-red); font-size: 12px; font-weight: 500; cursor: pointer; }
.consult-delete-button:hover:not(:disabled) { background: var(--status-red-bg); }
.consult-delete-button:disabled { opacity: .5; cursor: not-allowed; }
.consult-delete-button:focus-visible { outline: 2px solid var(--theme-blue); outline-offset: 3px; }
.consultation-detail { color: var(--text-primary); overflow-wrap: anywhere; }
.detail-heading { padding-bottom: 24px; }
.detail-heading h3 { margin: 14px 0 8px; font-size: 22px; font-weight: 600; line-height: 1.4; letter-spacing: -.4px; }
.detail-heading .consult-time { margin: 0; font-size: 12px; }
.consult-status { display: inline-flex; align-items: center; gap: 6px; padding: 5px 10px; border-radius: var(--radius-full); background: #f5f5f7; color: var(--text-secondary); font-size: 11px; font-weight: 500; }
.consult-status::before { content: ''; width: 5px; height: 5px; border-radius: 50%; background: currentColor; }
.consult-status.wait_reply { color: var(--text-link); background: #edf5ff; }
.consult-status.replied { color: #24834b; background: #edf8f0; }
.detail-question { padding: 20px; border: 1px solid var(--border-divider); border-radius: var(--radius-md); background: #f9f9fb; }
.detail-question h4, .detail-section-heading h4 { margin: 0; font-size: 13px; font-weight: 600; }
.detail-question p, .reply-body p { margin: 10px 0 0; font-size: 14px; line-height: 1.8; white-space: pre-wrap; }
.detail-responses { padding-top: 28px; }
.detail-section-heading { display: flex; align-items: center; justify-content: space-between; margin-bottom: 20px; }
.detail-section-heading span, .reply-meta time { color: var(--text-tertiary); font-size: 11px; }
.detail-replies { display: grid; gap: 24px; }
.detail-reply { display: flex; gap: 12px; }
.reply-avatar { flex-shrink: 0; display: grid; place-items: center; width: 32px; height: 32px; border-radius: 50%; background: #edf5ff; color: var(--text-link); font-size: 12px; font-weight: 600; }
.reply-body { min-width: 0; flex: 1; }
.reply-meta { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 6px 12px; min-height: 32px; }
.reply-meta strong { font-size: 13px; font-weight: 500; }
.reply-body p { margin-top: 4px; color: var(--text-secondary); }
.detail-empty { padding: 24px 16px; margin: 0; border-radius: var(--radius-md); background: #f5f5f7; text-align: center; color: var(--text-secondary); font-size: 13px; line-height: 1.6; }
.detail-actions { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 12px; }
.detail-actions > button { box-sizing: border-box; width: 112px; height: 40px; min-height: 40px; padding: 0 16px; border: 1px solid transparent; border-radius: var(--radius-full); font-size: 13px; font-weight: 500; line-height: 1; }
.detail-actions .secondary-button { margin-left: auto; }
.detail-footer .form-error { text-align: left; margin: 0 0 12px; }
.detail-closed-note { color: var(--text-tertiary); font-size: 12px; }
.detail-actions .close-consultation-button { display: inline-flex; align-items: center; justify-content: center; border-color: var(--status-red); background: #fff; color: var(--status-red); cursor: pointer; transition: background-color .15s ease; }
.close-consultation-button:hover:not(:disabled) { background: var(--status-red-bg); }
.close-consultation-button:disabled { opacity: .5; cursor: not-allowed; }
.close-consultation-button:focus-visible, .consult-detail-button:focus-visible { outline: 2px solid var(--theme-blue); outline-offset: 3px; }
.consultation-layout {
  display: grid;
  grid-template-columns: 1.2fr 1fr;
  gap: 28px;
  align-items: flex-start;
}

.settings-heading {
  margin-bottom: 16px;
  padding-bottom: 12px;
  border-bottom: 1px solid #d9d9df;
}

.settings-heading h2 { font-size: 18px; }

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

<style>
.el-dialog.consultation-dialog { padding: 0; border: 1px solid var(--border-divider); border-radius: 20px; box-shadow: var(--shadow-popover); overflow: hidden; }
.consultation-dialog .el-dialog__header { margin: 0; padding: 24px 56px 20px 28px; border-bottom: 1px solid var(--border-divider); }
.consultation-dialog .el-dialog__title { color: var(--text-primary); font-size: 16px; font-weight: 600; }
.consultation-dialog .el-dialog__headerbtn { top: 14px; right: 14px; }
.consultation-dialog .el-dialog__body { padding: 24px 28px 28px; max-height: 60vh; max-height: 60dvh; overflow-y: auto; }
.consultation-dialog .el-dialog__footer { padding: 18px 28px; border-top: 1px solid var(--border-divider); }
@media (max-width: 600px) {
  .consultation-dialog .el-dialog__header { padding-left: 20px; }
  .consultation-dialog .el-dialog__body { padding: 20px; }
  .consultation-dialog .el-dialog__footer { padding: 16px 20px; }
}
</style>
