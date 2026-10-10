<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { adminApi } from '@/api/modules'
import RequestState from '@/components/RequestState.vue'
import { fetchAllPages } from '@/utils/paging'
import { codePointLength } from '@/utils/text'

const rows = ref([])
const loading = ref(false)
const error = ref('')
const updating = ref(null)

const query = reactive({ page: 1, size: 20, status: '' })
const total = ref(0)
const totalPages = ref(0)

const STATUS_LABEL = { DRAFT: '草稿', PUBLISHED: '已发布', OFFLINE: '已下线' }
const statusLabel = (status) => STATUS_LABEL[status] || status
const statusClass = (status) =>
  status === 'PUBLISHED' ? 'success' : status === 'OFFLINE' ? 'danger' : 'warning'

// ---------------- 表单弹窗 ----------------
const dialogVisible = ref(false)
const saving = ref(false)
const formError = ref('')
const editingId = ref(null)

const TITLE_MIN = 2
const TITLE_MAX = 200
const SUMMARY_MAX = 500
const CONTENT_MAX = 100000
const CITY_MAX = 64
const DESTINATION_MAX = 128
const COVER_MAX = 500

const form = reactive({
  title: '',
  summary: '',
  content: '',
  city: '',
  destination: '',
  attractionId: '',
  coverUrl: ''
})

// 关联景点的候选项（启用 + 有坐标），供下拉选择
const attractionOptions = ref([])
const optionsLoading = ref(false)
const optionsError = ref('')

const hasCoordinates = (a) => a?.longitude != null && a?.latitude != null

async function loadOptions() {
  optionsLoading.value = true
  optionsError.value = ''
  try {
    const all = await fetchAllPages(adminApi.attractions)
    attractionOptions.value = all.filter((item) => item.status === 'ACTIVE' && hasCoordinates(item))
  } catch {
    optionsError.value = '候选景点加载失败'
  } finally {
    optionsLoading.value = false
  }
}

async function load(retryOnEmptyPage = true) {
  loading.value = true
  error.value = ''
  try {
    const result = await adminApi.articles({
      page: query.page,
      size: query.size,
      status: query.status || undefined
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
    error.value = cause.message || '攻略列表加载失败'
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

function openCreate() {
  editingId.value = null
  formError.value = ''
  Object.assign(form, {
    title: '', summary: '', content: '', city: '', destination: '', attractionId: '', coverUrl: ''
  })
  dialogVisible.value = true
  if (!attractionOptions.value.length) loadOptions()
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
      // 关键：把 attractionId 带进来，防止编辑时被提交为 null 清空
      attractionId: detail?.attractionId ? String(detail.attractionId) : '',
      coverUrl: detail?.coverUrl || ''
    })
    dialogVisible.value = true
    if (!attractionOptions.value.length) loadOptions()
  } catch (cause) {
    ElMessage.error(cause.message || '攻略详情加载失败')
  }
}

function optional(value) {
  const text = String(value ?? '').trim()
  return text === '' ? null : text
}

function coverError(value) {
  const text = String(value ?? '').trim()
  if (text === '') return ''
  if (codePointLength(text) > COVER_MAX) return `封面地址最多 ${COVER_MAX} 个字符`
  try {
    const url = new URL(text)
    if (url.protocol !== 'http:' && url.protocol !== 'https:') return '封面地址需为 http(s) 链接'
  } catch {
    return '封面地址需为合法的 http(s) 链接'
  }
  return ''
}

function validate() {
  const title = form.title.trim()
  const content = form.content.trim()
  if (codePointLength(title) < TITLE_MIN) return `标题至少 ${TITLE_MIN} 个字符`
  if (codePointLength(title) > TITLE_MAX) return `标题最多 ${TITLE_MAX} 个字符`
  if (codePointLength(form.summary) > SUMMARY_MAX) return `摘要最多 ${SUMMARY_MAX} 个字符`
  if (!content) return '正文不能为空'
  if (codePointLength(content) > CONTENT_MAX) return `正文最多 ${CONTENT_MAX} 个字符`
  if (codePointLength(form.city) > CITY_MAX) return `城市最多 ${CITY_MAX} 个字符`
  if (codePointLength(form.destination) > DESTINATION_MAX) return `目的地最多 ${DESTINATION_MAX} 个字符`
  const cover = coverError(form.coverUrl)
  if (cover) return cover
  return ''
}

async function save() {
  if (saving.value) return
  formError.value = ''
  const invalid = validate()
  if (invalid) return ElMessage.warning(invalid)

  const payload = {
    title: form.title.trim(),
    summary: optional(form.summary),
    content: form.content.trim(),
    city: optional(form.city),
    destination: optional(form.destination),
    // 关键字段：必须提交，否则后端会清空原有景点关联
    attractionId: form.attractionId ? String(form.attractionId) : null,
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
      type: 'warning', confirmButtonText: '确认删除', cancelButtonText: '取消'
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
        <p>创建、编辑旅游攻略，支持发布/下线与删除草稿。</p>
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
        <button type="submit" class="secondary-button" :disabled="loading">查询</button>
      </form>

      <RequestState
        :loading="loading"
        :error="error"
        :empty="!loading && !error && rows.length === 0"
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
                <span class="tag" :class="statusClass(row.status)">{{ statusLabel(row.status) }}</span>
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
                  {{ updating === row.id
                    ? '提交中…'
                    : row.status === 'PUBLISHED' ? '下线' : '发布' }}
                </button>
                <span class="divider">|</span>
                <button
                  type="button"
                  class="text-button text-danger"
                  :disabled="row.status !== 'DRAFT'"
                  @click="remove(row)"
                >删除</button>
              </td>
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

    <el-dialog
      v-model="dialogVisible"
      :title="editingId ? '编辑攻略' : '新增攻略'"
      width="min(760px, calc(100vw - 32px))"
      :close-on-click-modal="!saving"
      :show-close="!saving"
    >
      <div class="dialog-form-grid">
        <p v-if="formError" class="form-error wide" role="alert">{{ formError }}</p>

        <div class="form-field wide">
          <label>标题 <span class="req">*</span></label>
          <input v-model="form.title" placeholder="例如：初次到云南旅行实用指南" />
          <p class="form-counter" :class="{ over: codePointLength(form.title) > TITLE_MAX }">
            {{ codePointLength(form.title) }} / {{ TITLE_MAX }}（至少 {{ TITLE_MIN }}）
          </p>
        </div>

        <div class="form-field wide">
          <label>摘要</label>
          <input v-model="form.summary" placeholder="可选，不超过 500 字" />
          <p class="form-counter" :class="{ over: codePointLength(form.summary) > SUMMARY_MAX }">
            {{ codePointLength(form.summary) }} / {{ SUMMARY_MAX }}
          </p>
        </div>

        <div class="form-field">
          <label>城市</label>
          <input v-model="form.city" placeholder="可选，例如：昆明" />
          <p class="form-counter" :class="{ over: codePointLength(form.city) > CITY_MAX }">
            {{ codePointLength(form.city) }} / {{ CITY_MAX }}
          </p>
        </div>

        <div class="form-field">
          <label>目的地</label>
          <input v-model="form.destination" placeholder="可选，例如：云南" />
          <p class="form-counter" :class="{ over: codePointLength(form.destination) > DESTINATION_MAX }">
            {{ codePointLength(form.destination) }} / {{ DESTINATION_MAX }}
          </p>
        </div>

        <div class="form-field wide">
          <label>关联景点</label>
          <select v-model="form.attractionId">
            <option value="">不关联</option>
            <option
              v-for="attraction in attractionOptions"
              :key="attraction.id"
              :value="String(attraction.id)"
            >
              {{ attraction.name }}（{{ attraction.city }}）
            </option>
          </select>
          <p class="form-hint">
            <span v-if="optionsLoading">正在加载景点候选…</span>
            <span v-else-if="optionsError" class="hint-error" role="alert">{{ optionsError }}</span>
            <span v-else>关联后会在用户端攻略详情页展示该景点卡片（可留空）。</span>
          </p>
        </div>

        <div class="form-field wide">
          <label>封面图 URL</label>
          <input v-model="form.coverUrl" placeholder="可选，https://…" />
          <p class="form-counter" :class="{ over: codePointLength(form.coverUrl) > COVER_MAX }">
            {{ codePointLength(form.coverUrl) }} / {{ COVER_MAX }}
          </p>
        </div>

        <div class="form-field wide">
          <label>正文 <span class="req">*</span></label>
          <textarea v-model="form.content" rows="12" placeholder="攻略正文内容"></textarea>
          <p class="form-counter" :class="{ over: codePointLength(form.content) > CONTENT_MAX }">
            {{ codePointLength(form.content) }} / {{ CONTENT_MAX }}
          </p>
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

.dialog-form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px 20px;   /* 行间距 16px、列间距 20px，比原来宽松 */
}

.dialog-form-grid .wide {
  grid-column: 1 / -1;
}

.form-field {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 12px 14px;              /* 每个字段独立卡片感 */
  border: 1px solid var(--border-divider, #ececf0);
  border-radius: 10px;
  background: #fbfcfe;
}

.form-field label {
  font-size: 12px;
  color: var(--text-secondary);
  font-weight: 600;
}

.form-field input,
.form-field textarea,
.form-field select {
  padding: 8px 10px;
  border: 1px solid var(--border-strong);
  border-radius: 8px;
  background: #fff;
  font-family: inherit;
  font-size: 13px;
  color: var(--text-primary);
}

.form-field textarea {
  resize: vertical;
}