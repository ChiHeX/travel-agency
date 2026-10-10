<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { adminApi } from '@/api/modules'
import AttractionFormDialog from '@/components/AttractionFormDialog.vue'
import HotelFormDialog from '@/components/HotelFormDialog.vue'
import DepartureFormDialog from '@/components/DepartureFormDialog.vue'

const props = defineProps({
  title: { type: String, required: true },
  resource: { type: String, required: true }
})

const rows = ref([])
const loading = ref(false)
/** 正在提交审核的退款单 id；用来禁用按钮，防止重复点出两次出款请求。 */
const pending = ref(null)
/** 正在删除的行 id。 */
const deleting = ref(null)

// ---------- 表单弹窗 ----------
const attractionDialogVisible = ref(false)
const hotelDialogVisible = ref(false)
const departureDialogVisible = ref(false)
const editingRow = ref(null)

const loaders = {
  attractions: adminApi.attractions,
  hotels: adminApi.hotels,
  guides: adminApi.guides,
  departures: adminApi.departures,
  refunds: adminApi.refunds
}

/** 支持新增/编辑的资源。guides 已由独立页面处理；refunds 是审核流。 */
const canManage = computed(() => ['attractions', 'hotels', 'departures'].includes(props.resource))

const REFUND_STATUS_LABEL = {
  APPLYING: '待审核',
  PROCESSING: '退款结果待确认',
  REFUNDED: '已退款',
  REJECTED: '已拒绝'
}

const refundLabel = (status) => REFUND_STATUS_LABEL[status] || status
const refundTagClass = (status) =>
  status === 'REFUNDED' ? 'success' : status === 'REJECTED' ? 'danger' : 'warning'

async function load() {
  loading.value = true
  try {
    rows.value = (await loaders[props.resource]())?.items || []
  } finally {
    loading.value = false
  }
}

async function updateDeparture(row) {
  const next = row.status === 'OPEN' ? 'CLOSED' : 'OPEN'
  await adminApi.updateDepartureStatus(row.id, next)
  row.status = next
  ElMessage.success('团期状态已更新')
}

async function decision(row, action) {
  if (pending.value != null) return
  pending.value = row.id
  const comment = action === 'APPROVE' ? '审核通过，已进入原路退款流程' : '申请原因需要进一步核实'
  try {
    if (action === 'APPROVE') await adminApi.approveRefund(row.id, comment)
    else await adminApi.rejectRefund(row.id, comment)
    ElMessage.success(action === 'APPROVE' ? '退款审核已处理完毕' : '退款申请已驳回')
  } catch {
    // 失败提示由 axios 拦截器统一弹出。
  } finally {
    pending.value = null
    await load().catch(() => {})
  }
}

// ---------- 新增/编辑 ----------

function openCreate() {
  editingRow.value = null
  if (props.resource === 'attractions') attractionDialogVisible.value = true
  else if (props.resource === 'hotels') hotelDialogVisible.value = true
  else if (props.resource === 'departures') departureDialogVisible.value = true
}

function openEdit(row) {
  editingRow.value = row
  if (props.resource === 'attractions') attractionDialogVisible.value = true
  else if (props.resource === 'hotels') hotelDialogVisible.value = true
  else if (props.resource === 'departures') departureDialogVisible.value = true
}

async function remove(row) {
  if (deleting.value) return
  if (props.resource === 'departures') return
  const deleteApi =
    props.resource === 'attractions' ? adminApi.deleteAttraction : adminApi.deleteHotel
  try {
    await ElMessageBox.confirm(
      `确认删除「${row.name}」吗？如已被行程引用，将无法删除。`,
      '删除资料',
      { type: 'warning', confirmButtonText: '确认删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  deleting.value = row.id
  try {
    await deleteApi(row.id)
    ElMessage.success('已删除')
    await load()
  } catch (cause) {
    if (cause.status === 409) ElMessage.warning(cause.message || '该资料已被行程引用，无法删除')
    else ElMessage.error(cause.message || '删除失败')
  } finally {
    deleting.value = null
  }
}

watch(() => props.resource, load)
onMounted(load)
</script>

<template>
  <div class="admin-resources-page">
    <div class="admin-page-head">
      <div>
        <h2>{{ title }}</h2>
        <p>维护基础业务资源档案、可追溯资料与审核流。</p>
      </div>
      <button v-if="canManage" class="primary-button" @click="openCreate">
        + 新增{{ resource === 'attractions' ? '景点' : resource === 'hotels' ? '酒店' : '团期' }}
      </button>
    </div>

    <div class="admin-panel">
      <div v-if="loading">
        <el-skeleton :rows="8" animated />
      </div>

      <table v-else-if="rows.length" class="data-table">
        <thead>
          <tr v-if="resource === 'departures'">
            <th>线路编号</th>
            <th>出发日期</th>
            <th>返程日期</th>
            <th>成人价格</th>
            <th>已确认 / 最大容纳</th>
            <th>状态</th>
            <th style="text-align: right;">操作</th>
          </tr>
          <tr v-else-if="resource === 'refunds'">
            <th>退款申请单号</th>
            <th>关联订单号</th>
            <th>申请金额</th>
            <th>申请退款原因</th>
            <th>审核状态</th>
            <th style="text-align: right;">操作</th>
          </tr>
          <tr v-else-if="resource === 'guides'">
            <th>导游姓名</th>
            <th>联系电话</th>
            <th>个人专长简介</th>
            <th>状态</th>
          </tr>
          <tr v-else>
            <th>名称</th>
            <th>所属城市 / 地址</th>
            <th>地理经纬度</th>
            <th>资料来源</th>
            <th>状态</th>
            <th v-if="canManage" style="text-align: right;">操作</th>
          </tr>
        </thead>
        <tbody>
          <template v-for="row in rows" :key="row.id">
            <tr v-if="resource === 'departures'">
              <td><strong>线路 #{{ row.routeId }}</strong></td>
              <td>{{ row.startDate }}</td>
              <td>{{ row.endDate }}</td>
              <td class="amount">¥{{ row.adultPrice }}</td>
              <td>
                {{ row.confirmedPeople == null ? '—' : row.confirmedPeople }} /
                {{ row.maxPeople == null ? '—' : row.maxPeople }}
                <span v-if="row.confirmedPeople != null || row.maxPeople != null"> 人</span>
              </td>
              <td>
                <span class="tag" :class="row.status === 'OPEN' ? 'success' : row.status === 'CLOSED' ? 'danger' : 'warning'">
                  {{ row.status }}
                </span>
              </td>
              <td style="text-align: right;" class="row-actions">
                <button type="button" class="text-button" @click="openEdit(row)">编辑</button>
                <span class="divider">|</span>
                <button type="button" class="text-button" @click="updateDeparture(row)">
                  {{ row.status === 'OPEN' ? '关闭报名' : '开放报名' }}
                </button>
              </td>
            </tr>

            <tr v-else-if="resource === 'refunds'">
              <td><strong>#{{ row.id }}</strong></td>
              <td>#{{ row.orderNo }}</td>
              <td class="amount">¥{{ row.amount }}</td>
              <td>{{ row.reason }}</td>
              <td>
                <span class="tag" :class="refundTagClass(row.status)">
                  {{ refundLabel(row.status) }}
                </span>
              </td>
              <td style="text-align: right;">
                <template v-if="row.status === 'APPLYING'">
                  <button type="button" class="text-button text-success" :disabled="pending === row.id" @click="decision(row, 'APPROVE')">同意退款</button>
                  <span class="divider">|</span>
                  <button type="button" class="text-button text-danger" :disabled="pending === row.id" @click="decision(row, 'REJECT')">拒绝</button>
                </template>
                <template v-else-if="row.status === 'PROCESSING'">
                  <button type="button" class="text-button text-success" :disabled="pending === row.id" @click="decision(row, 'APPROVE')">
                    {{ pending === row.id ? '确认中…' : '重试确认' }}
                  </button>
                </template>
                <span v-else class="muted-text">已处理完毕</span>
              </td>
            </tr>

            <tr v-else-if="resource === 'guides'">
              <td><strong>{{ row.name }}</strong></td>
              <td>{{ row.phone || '—' }}</td>
              <td>{{ row.intro || '—' }}</td>
              <td><span class="tag success">{{ row.status }}</span></td>
            </tr>

            <tr v-else>
              <td><strong>{{ row.name }}</strong></td>
              <td>{{ row.city || row.address || '—' }}</td>
              <td>{{ row.longitude || '—' }}, {{ row.latitude || '—' }}</td>
              <td>{{ row.dataSource || '—' }}</td>
              <td><span class="tag success">{{ row.status }}</span></td>
              <td v-if="canManage" style="text-align: right;" class="row-actions">
                <button type="button" class="text-button" @click="openEdit(row)">编辑</button>
                <span class="divider">|</span>
                <button
                  type="button"
                  class="text-button text-danger"
                  :disabled="deleting === row.id"
                  @click="remove(row)"
                >
                  {{ deleting === row.id ? '删除中…' : '删除' }}
                </button>
              </td>
            </tr>
          </template>
        </tbody>
      </table>

      <div v-else class="empty-box">暂无相关资料数据。</div>
    </div>

    <AttractionFormDialog
      v-if="resource === 'attractions'"
      v-model="attractionDialogVisible"
      :attraction="editingRow"
      @saved="load"
    />
    <HotelFormDialog
      v-if="resource === 'hotels'"
      v-model="hotelDialogVisible"
      :hotel="editingRow"
      @saved="load"
    />
    <DepartureFormDialog
      v-if="resource === 'departures'"
      v-model="departureDialogVisible"
      :departure="editingRow"
      @saved="load"
    />
  </div>
</template>

<style scoped>
.amount {
  color: var(--price-orange);
  font-weight: 800;
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

.muted-text {
  color: var(--text-tertiary);
  font-size: 12px;
}

.row-actions {
  white-space: nowrap;
}
</style>