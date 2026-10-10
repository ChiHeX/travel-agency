<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { adminApi } from '@/api/modules'
import RequestState from '@/components/RequestState.vue'

const rows = ref([])
const total = ref(0)
const loading = ref(false)
const error = ref('')
const updating = ref(null)

const query = reactive({ page: 1, size: 20, status: '' })

const statusNames = { DRAFT: '草稿', PUBLISHED: '已发布', OFFLINE: '已下线' }
const statusClasses = { DRAFT: 'warning', PUBLISHED: 'success', OFFLINE: 'danger' }

const dialogVisible = ref(false)
const saving = ref(false)
const formError = ref('')
const editingId = ref(null)

const form = reactive({
  title: '',
  summary: '',
  content: '',
  city: '',
  destination: '',
  coverUrl: ''
})

const isEmpty = computed(() => !loading.value && !error.value && rows.value.length === 0)

async function load() {
  loading.value = true
  error.value = ''
  try {
    const data = await adminApi.articles({
      page: query.page,
      size: query.size,
      status: query.status || undefined
    })
    rows.value = data?.items || []
    total.value = data?.total || 0
  } catch (cause) {
    error.value = cause.message || '攻略列表加载失败'
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

function openCreate() {
  editingId.value = null
  formError.value = ''
  Object.assign(form, { title: '', summary: '', content: '', city: '', destination: '', coverUrl: '' })
  dialogVisible.value = true
}

async function openEdit(row) {
  editingId.value = row.id
  formError.value = ''
  try {
    const detail = await adminApi.article(row.id)
    Object.assign(form, {
      title: detail?.title || '',
      summary: detail?.summary || '',
      content: detail?.content || '',
      city: detail?.city || '',
      destination: detail?.destination || '',
      coverUrl: detail?.coverUrl || ''
    })
    dialogVisible.value = true
  } catch (cause) {
    ElMessage.error(cause.message || '攻略详情加载失败')
  }
}

function optional(value) {
  const trimmed = (value || '').toString().trim()
  return trimmed === '' ? null : trimmed
}

async function save() {
  if (saving.value) return
  formError.value = ''
  const title = form.title.trim()
  const content = form.content.trim()
  if (title.length < 2) {
    formError.value = '标题至少 2 个字'
    return
  }
  if (!content) {
    formError.value = '内容不能为空'
    return
  }

  const payload = {
    title,
    summary: optional(form.summary),
    content,
    city: optional(form.city),
    destination: optional(form.destination),
    coverUrl: optional(form.coverUrl)
  }

  saving.value = true
  try {
    if (editingId.value) {
      await adminApi.updateArticle(editingId.value, payload)
      ElMessage.success('攻略已更新')
    } else {
      await adminApi.createArticle(payload)
      ElMessage.success('攻略草稿已创建')
    }
    dialogVisible.value = false
    await load()
  } catch (cause) {
    formError.value = cause.message || '保存失败'
  } finally {
    saving.value = false
  }
}

async function toggleStatus(row) {
  if (updating.value) return
  const next = row.status === 'PUBLISHED' ? 'OFFLINE' : 'PUBLISHED'
  if (next === 'OFFLINE') {
    try {
      await ElMessageBox.confirm(`确认下线攻略「${row.title}」吗？`, '下线攻略', {
        type: 'warning',
        confirmButtonText: '确认下线',
        cancelButtonText: '取消'
      })
    } catch {
      return
    }
  }
  updating.value = row.id
  try {
    await adminApi.updateArticleStatus(row.id, next)
    ElMessage.success(next === 'PUBLISHED' ? '攻略已发布' : '攻略已下线')
    await load()
  } catch (cause) {
    if (cause.status === 409) ElMessage.warning(cause.message || '当前状态不允许该操作')
    else ElMessage.error(cause.message || '状态更新失败')
  } finally {
    updating.value = null
  }
}

async function remove(row) {
  if (row.status !== 'DRAFT') {
    ElMessage.warning('只能删除未发布的攻略草稿')
    return
  }
  try {
    await ElMessageBox.confirm(`确认删除攻略「${row.title}」吗？`, '删除攻略', {
      type: 'warning',
      confirmButtonText: '确认删除',
      cancelButtonText: '取消'
    })
  } catch {
    return
  }
  try {
    await adminApi.deleteArticle(row.id)
    ElMessage.success('攻略已删除')
    await load()
  } catch (cause) {
    if (cause.status === 409) ElMessage.warning(cause.message || '已发布的攻略不能删除')
    else ElMessage.error(cause.message || '删除失败')
  }
}

onMounted(load)
</script>

<template>
  <div class="admin-articles-page">
    <div class="admin-page-head">
      <div>
        <h2>旅游攻略管理</h2>
        <p>创建和维护旅游攻略，发布后对游客可见。</p>
      </div>
      <button class="primary-button" @click="openCreate">+ 新增攻略</button>
    </div>

    <div class="admin-panel">
      <form class="toolbar" @submit.prevent="search">
        <select v-model="query.status" class="filter-select">
          <option value="">全部状态</option>
          <option value="DRAFT">草稿</option>
          <option value="PUBLISHED">已发布</option>
          <option value="OFFLINE">已下线</option>
        </select>
        <button type="submit" class="secondary-button">查询</button>
      </form>

      <RequestState
        :loading="loading"
        :error="error"
        :empty="isEmpty"
        empty-text="暂无攻略数据。"
        @retry="load"
      >
        <table class="data-table">
          <thead>
            <tr>
              <th>ID</th>
              <th>标题</th>
              <th>城市</th>
              <th>目的地</th>
              <th>作者</th>
              <th>状态</th>
              <th>发布时间</th>
              <th style="text-align: right;">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in rows" :key="row.id">
              <td>#{{ row.id }}</td>
              <td>{{ row.title }}</td>
              <td>{{ row.city || '—' }}</td>
              <td>{{ row.destination || '—' }}</td>
              <td>{{ row.authorName }}</td>
              <td>
                <span class="tag" :class="statusClasses[row.status] || ''">
                  {{ statusNames[row.status] || row.status }}
                </span>
              </td>
              <td>{{ row.publishedAt || '—' }}</td>
              <td style="text-align: right;" class="row-actions">
                <button type="button" class="text-button" @click="openEdit(row)">编辑</button>
                <span class="divider">|</span>
                <button
                  type="button"
                  class="text-button"
                  :disabled="updating === row.id"
                  @click="toggleStatus(row)"
                >
                  {{ row.status === 'PUBLISHED' ? '下线' : '发布' }}
                </button>
                <span class="divider">|</span>
                <button
                  type="button"
                  class="text-button text-danger"
                  :disabled="row.status !== 'DRAFT'"
                  @click="remove(row)"
                >
                  删除
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

    <el-dialog
      v-model="dialogVisible"
      :title="editingId ? '编辑攻略' : '新增攻略'"
      width="min(720px, calc(100vw - 32px))"
      :close-on-click-modal="!saving"
    >
      <div class="dialog-form">
        <p v-if="formError" class="form-error" role="alert">{{ formError }}</p>

        <div class="form-field">
          <label>标题 <span class="req">*</span></label>
          <input v-model="form.title" maxlength="200" placeholder="2-200 字" />
        </div>

        <div class="form-field">
          <label>摘要</label>
          <input v-model="form.summary" maxlength="500" placeholder="可选，不超过 500 字" />
        </div>

        <div class="form-row">
          <div class="form-field">
            <label>城市</label>
            <input v-model="form.city" maxlength="64" placeholder="可选" />
          </div>
          <div class="form-field">
            <label>目的地</label>
            <input v-model="form.destination" maxlength="128" placeholder="可选" />
          </div>
        </div>

        <div class="form-field">
          <label>封面图 URL</label>
          <input v-model="form.coverUrl" maxlength="500" placeholder="可选，https://..." />
        </div>

        <div class="form-field">
          <label>正文 <span class="req">*</span></label>
          <textarea v-model="form.content" rows="10" placeholder="攻略正文内容"></textarea>
        </div>
      </div>

      <template #footer>
        <button class="secondary-button" :disabled="saving" @click="dialogVisible = false">取消</button>
        <button class="primary-button" :disabled="saving" @click="save">
          {{ saving ? '保存中…' : '保存' }}
        </button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.toolbar { display: flex; gap: 10px; margin-bottom: 20px; }
.divider { color: var(--border-strong); margin: 0 6px; font-size: 11px; }
.text-danger { color: var(--danger-red) !important; }
.row-actions { white-space: nowrap; }
.pagination-wrap { display: flex; justify-content: center; margin-top: 24px; }

.dialog-form { display: flex; flex-direction: column; gap: 14px; }
.form-row { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; }
.form-field { display: flex; flex-direction: column; gap: 6px; }
.form-field label { font-size: 12px; color: var(--text-secondary); }
.form-field input,
.form-field textarea { padding: 8px 10px; border: 1px solid var(--border-divider, #e5e7eb); border-radius: 6px; font-family: inherit; font-size: 13px; }
.form-field textarea { resize: vertical; }
.req { color: var(--danger-red, #dc2626); }
.form-error { margin: 0; color: var(--danger-red, #dc2626); font-size: 12px; }
</style>