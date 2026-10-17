<script setup>
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { adminApi } from '@/api/modules'
import RequestState from '@/components/RequestState.vue'
import PlaceGuideFormDialog from '@/components/PlaceGuideFormDialog.vue'

/**
 * 后台「地点指南管理」页，对齐契约的 Admin Place Guides 端点：
 * GET / PATCH / POST / PUT /admin/place-guides...
 *
 * <p>地点指南是用户端地图联动的精选内容：一份指南由 2–50 个<b>带坐标的已启用景点</b>组成，
 * 指南详情据此聚焦地图区域。此前这些端点只有后端实现，没有任何前端入口 ——
 * 指南只能靠直接改数据库维护，这里补上列表、新增、编辑与发布/下线。</p>
 *
 * <p>列表接口（GET /admin/place-guides）只声明 page / size，未声明状态或关键字筛选，
 * 因此页面不做契约外的查询参数；列表返回的 {@code places} 为空数组，地点在打开编辑时
 * 再通过 GET /admin/place-guides/{guideId} 取回（契约明确"列表页为空数组，详情页为完整地点"）。</p>
 */

const rows = ref([])
const loading = ref(false)
const error = ref('')

/** 正在取详情并准备打开编辑弹窗的指南 id（禁用按钮，避免重复点出两次请求）。 */
const preparing = ref(null)
/** 正在提交状态变更的指南 id 集合：不同指南的启停互不阻塞。 */
const pendingStatus = ref(new Set())

const page = ref(1)
const PAGE_SIZE = 20
const total = ref(0)
const totalPages = ref(0)

const dialogVisible = ref(false)
const editingGuide = ref(null)

const STATUS_LABEL = { DRAFT: '草稿', PUBLISHED: '已发布', OFFLINE: '已下线' }
const statusLabel = (status) => STATUS_LABEL[status] || status
const statusClass = (status) =>
  status === 'PUBLISHED' ? 'success' : status === 'OFFLINE' ? 'danger' : 'warning'

/**
 * 拉取列表。
 *
 * @param {boolean} retryOnEmptyPage 当前页取空且不是第一页时是否回退一页重取
 */
async function load(retryOnEmptyPage = true) {
  loading.value = true
  error.value = ''
  try {
    const result = await adminApi.placeGuides({ page: page.value, size: PAGE_SIZE })
    rows.value = result?.items || []
    total.value = Number(result?.total ?? rows.value.length)
    totalPages.value = Number(result?.totalPages ?? (rows.value.length ? 1 : 0))
    if (retryOnEmptyPage && !rows.value.length && page.value > 1) {
      page.value -= 1
      await load(false)
    }
  } catch (cause) {
    rows.value = []
    error.value = cause.message || '地点指南加载失败'
  } finally {
    loading.value = false
  }
}

function goToPage(delta) {
  const next = page.value + delta
  if (next < 1 || (totalPages.value && next > totalPages.value)) return
  page.value = next
  load()
}

/**
 * 打开编辑弹窗：列表的 places 恒为空数组，必须先取详情，否则编辑会把地点清空。
 * 详情里的 places 才是地图顺序的来源。
 */
async function openEditDialog(row) {
  if (preparing.value != null) return
  preparing.value = row.id
  try {
    const detail = await adminApi.placeGuide(row.id)
    editingGuide.value = detail
    dialogVisible.value = true
  } catch {
    // 失败提示已由 axios 拦截器统一弹出。
  } finally {
    preparing.value = null
  }
}

function openCreateDialog() {
  editingGuide.value = null
  dialogVisible.value = true
}

/** 新增回第 1 页（列表按发布时间倒序），修改留在当前页（发布/更新时间不影响排序）。 */
function onSaved(payload) {
  if (payload?.created) {
    page.value = 1
    load()
  } else {
    load()
  }
}

/**
 * 发布 / 下线，走契约 PATCH /admin/place-guides/{guideId}/status。
 *
 * <p>发布会让指南出现在用户端，下线会撤下，两者都先让运营确认一次；
 * 失败时把本地状态回滚为改动前的值，避免页面停在一个并未落库的状态上
 * （错误提示由 axios 拦截器统一弹出）。</p>
 */
async function changeStatus(row) {
  const next = row.status === 'PUBLISHED' ? 'OFFLINE' : 'PUBLISHED'
  const action = next === 'PUBLISHED' ? '发布' : '下线'
  try {
    await ElMessageBox.confirm(
      next === 'PUBLISHED'
        ? `确认发布指南「${row.title}」吗？发布后将出现在用户端指南页。`
        : `确认下线指南「${row.title}」吗？下线后用户端将不再展示。`,
      `${action}地点指南`,
      { type: 'warning', confirmButtonText: `确认${action}`, cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  if (pendingStatus.value.has(row.id)) return
  const previous = row.status
  pendingStatus.value.add(row.id)
  row.status = next
  try {
    const updated = await adminApi.updatePlaceGuideStatus(row.id, next)
    if (updated) Object.assign(row, updated)
    ElMessage.success(next === 'PUBLISHED' ? '地点指南已发布' : '地点指南已下线')
  } catch {
    row.status = previous
  } finally {
    pendingStatus.value.delete(row.id)
  }
}

const hasRows = computed(() => rows.value.length > 0)

onMounted(load)
</script>

<template>
  <div class="admin-place-guides">
    <div class="admin-page-head">
      <div>
        <h2>地点指南管理</h2>
        <p>编排用户端地图联动的精选地点清单（每份 2–50 个带坐标景点），并发布或下线。</p>
      </div>
      <button class="primary-button" @click="openCreateDialog">+ 新增地点指南</button>
    </div>

    <RequestState v-if="error" :error="error" @retry="load" />

    <div v-else class="admin-panel">
      <div v-if="loading">
        <el-skeleton :rows="8" animated />
      </div>

      <table v-else-if="hasRows" class="data-table">
        <thead>
          <tr>
            <th>指南标题</th>
            <th>所属城市 / 目的地</th>
            <th>发布者</th>
            <th>发布时间</th>
            <th>状态</th>
            <th style="text-align: right;">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in rows" :key="row.id">
            <td>
              <strong>{{ row.title }}</strong>
              <div class="muted-text">指南 #{{ row.id }}</div>
            </td>
            <td>
              {{ row.city || '—' }}
              <div class="muted-text">目的地：{{ row.destination || '—' }}</div>
            </td>
            <td>{{ row.authorName || '—' }}</td>
            <td>{{ row.publishedAt || '尚未发布' }}</td>
            <td>
              <span class="tag" :class="statusClass(row.status)">{{ statusLabel(row.status) }}</span>
            </td>
            <td style="text-align: right; white-space: nowrap;">
              <button
                type="button"
                class="text-button"
                :disabled="preparing === row.id"
                @click="openEditDialog(row)"
              >
                {{ preparing === row.id ? '加载中…' : '编辑' }}
              </button>
              <span class="divider">|</span>
              <button
                type="button"
                class="text-button"
                :class="row.status === 'PUBLISHED' ? 'text-danger' : 'text-success'"
                :disabled="pendingStatus.has(row.id)"
                @click="changeStatus(row)"
              >
                {{ pendingStatus.has(row.id)
                  ? '提交中…'
                  : row.status === 'PUBLISHED' ? '下线' : '发布' }}
              </button>
              <template v-if="row.status === 'PUBLISHED'">
                <span class="divider">|</span>
                <RouterLink class="text-button link-button" :to="`/guides/${row.id}`" target="_blank">
                  预览
                </RouterLink>
              </template>
            </td>
          </tr>
        </tbody>
      </table>

      <div v-else class="empty-box">
        暂无地点指南。点击「新增地点指南」创建草稿，选择至少两个带坐标的景点后发布。
      </div>

      <div class="resource-pager">
        <button
          type="button"
          class="secondary-button"
          :disabled="loading || page <= 1"
          @click="goToPage(-1)"
        >
          上一页
        </button>
        <span class="muted-text">
          第 {{ page }} / {{ totalPages || 1 }} 页，共 {{ total }} 条
        </span>
        <button
          type="button"
          class="secondary-button"
          :disabled="loading || page >= totalPages"
          @click="goToPage(1)"
        >
          下一页
        </button>
      </div>
    </div>

    <PlaceGuideFormDialog v-model="dialogVisible" :guide="editingGuide" @saved="onSaved" />
  </div>
</template>

<style scoped>
.admin-page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 18px;
}

.admin-page-head h2 {
  margin: 0 0 4px;
  font-size: 18px;
}

.admin-page-head p {
  margin: 0;
  color: var(--text-secondary);
  font-size: 13px;
}

.resource-pager {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 12px;
}

.divider {
  color: var(--border-strong);
  margin: 0 6px;
  font-size: 11px;
}

.text-success {
  color: var(--success-text) !important;
}

.text-danger {
  color: var(--danger-red) !important;
}

.link-button {
  text-decoration: none;
}

.muted-text {
  color: var(--text-tertiary);
  font-size: 12px;
}
</style>
