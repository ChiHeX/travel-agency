<script setup>
import { onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { adminApi } from '@/api/modules'
import DepartureFormDialog from '@/components/DepartureFormDialog.vue'

const props = defineProps({
  title: { type: String, required: true },
  resource: { type: String, required: true }
})

const rows = ref([])
const loading = ref(false)
/** 正在提交审核的退款单 id；用来禁用按钮，防止重复点出两次出款请求。 */
const pending = ref(null)

/**
 * 团期新增/编辑弹窗状态。线路与导游候选项由弹窗自己分页加载
 * （契约 Size 上限 100，放在这里只取第一页会让后续记录选不到）。
 */
const departureDialogVisible = ref(false)
const editingDeparture = ref(null)

const loaders = {
  attractions: adminApi.attractions,
  hotels: adminApi.hotels,
  guides: adminApi.guides,
  departures: adminApi.departures,
  refunds: adminApi.refunds
}

/** 契约 DepartureStatus 的全部取值，与后端枚举一一对应。 */
const DEPARTURE_STATUS_LABEL = {
  DRAFT: '草稿',
  OPEN: '报名中',
  FULL: '已满员',
  CLOSED: '已截止',
  TRAVELLING: '行程中',
  FINISHED: '已完成',
  CANCELLED: '已取消'
}
const DEPARTURE_STATUSES = Object.keys(DEPARTURE_STATUS_LABEL)

const departureStatusClass = (status) =>
  status === 'OPEN' ? 'success'
    : status === 'CANCELLED' || status === 'CLOSED' ? 'danger'
      : status === 'FINISHED' ? 'success' : 'warning'

/**
 * 退款状态文案。`PROCESSING` 特别重要：它不是「审核中」，而是**出款已发出、结果还没确认**
 * （钱可能已经退出去），后端会把它持久化，并且在确认之前禁止拒绝。
 */
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

/** 打开团期新增/编辑弹窗；候选项由弹窗自行分页加载。 */
function openDepartureDialog(row) {
  editingDeparture.value = row || null
  departureDialogVisible.value = true
}

/**
 * 修改团期运营状态，走契约 PATCH /admin/departures/{departureId}/status。
 *
 * 失败时把本地状态回滚为改动前的值，避免页面停留在一个并未落库的状态上；
 * 错误提示由 axios 拦截器统一弹出（见 frontend/src/api/request.js），这里不重复提示。
 */
async function changeDepartureStatus(row, next) {
  if (!next || next === row.status) return
  const previous = row.status
  row.status = next
  try {
    const updated = await adminApi.updateDepartureStatus(row.id, next)
    if (updated) Object.assign(row, updated)
    ElMessage.success('团期状态已更新')
  } catch {
    row.status = previous
  }
}

/**
 * 提交一次退款审核动作。
 *
 * <p>两种情况都要重新拉列表，因此刷新放在 finally 里：
 * ① 成功 —— 状态已从 APPLYING 变成 REFUNDED / REJECTED；
 * ② 失败且是「结果未确认」（后端 503 `REFUND_RESULT_UNCONFIRMED`）—— 此时出款请求已经发出去，
 * 后端把退款单落成了持久的 PROCESSING，页面若还停在旧的 `APPLYING` 上，
 * 管理员就会对着一个早已不接受拒绝的单子继续点「拒绝」（后端会回 409，白点一次）。</p>
 *
 * <p>{@code PROCESSING} 的重试走的就是 APPROVE：后端对已处于 PROCESSING 的单子放行同意、
 * 拦掉拒绝，用同一个出款请求号再确认一次结果。</p>
 */
async function decision(row, action) {
  if (pending.value != null) return
  pending.value = row.id
  const comment = action === 'APPROVE' ? '审核通过，已进入原路退款流程' : '申请原因需要进一步核实'
  try {
    if (action === 'APPROVE') await adminApi.approveRefund(row.id, comment)
    else await adminApi.rejectRefund(row.id, comment)
    ElMessage.success(action === 'APPROVE' ? '退款审核已处理完毕' : '退款申请已驳回')
  } catch {
    // 失败提示由 axios 拦截器统一弹出（frontend/src/api/request.js 的 ElMessage.error）；
    // 这里不重抛，避免在点击处理器里留下未处理的 Promise rejection。
  } finally {
    pending.value = null
    await load().catch(() => {})
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
      <button
        v-if="resource === 'departures'"
        class="primary-button"
        @click="openDepartureDialog(null)"
      >
        + 新增团期
      </button>
      <button
        v-else-if="resource !== 'refunds'"
        class="primary-button"
        @click="ElMessage.info('新增表单已对接对应后端 CRUD API')"
      >
        + 新增{{ title.replace('管理', '').replace('资料', '') }}
      </button>
    </div>

    <div class="admin-panel">
      <div v-if="loading">
        <el-skeleton :rows="8" animated />
      </div>

      <table v-else-if="rows.length" class="data-table">
        <thead>
          <tr v-if="resource === 'departures'">
            <th>所属线路</th>
            <th>出发日期</th>
            <th>返程日期</th>
            <th>成人价 / 儿童价</th>
            <th>已占用 / 名额上限</th>
            <th>带团导游</th>
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
          </tr>
        </thead>
        <tbody>
          <template v-for="row in rows" :key="row.id">
            <tr v-if="resource === 'departures'">
              <td>
                <strong>{{ row.routeName || `线路 #${row.routeId}` }}</strong>
                <div class="muted-text">团期 #{{ row.id }}</div>
              </td>
              <td>{{ row.startDate }}</td>
              <td>{{ row.endDate }}</td>
              <td class="amount">
                ¥{{ row.adultPrice }}
                <div class="muted-text">儿童 ¥{{ row.childPrice }}</div>
              </td>
              <td>
                {{ (row.reservedPeople || 0) + (row.confirmedPeople || 0) }} / {{ row.maxPeople }} 人
                <div class="muted-text">余位 {{ row.availableSeats }} 人</div>
              </td>
              <td>{{ row.guideName || '未分配' }}</td>
              <td>
                <span class="tag" :class="departureStatusClass(row.status)">
                  {{ DEPARTURE_STATUS_LABEL[row.status] || row.status }}
                </span>
              </td>
              <td style="text-align: right;">
                <select
                  class="status-select"
                  :value="row.status"
                  @change="changeDepartureStatus(row, $event.target.value)"
                >
                  <option v-for="status in DEPARTURE_STATUSES" :key="status" :value="status">
                    {{ DEPARTURE_STATUS_LABEL[status] }}
                  </option>
                </select>
                <span class="divider">|</span>
                <button type="button" class="text-button" @click="openDepartureDialog(row)">编辑</button>
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
                <!--
                  出款已发出、结果未确认。钱可能已经退出去，所以这里只给「重试确认」
                  （同一个出款请求号再查一次结果），绝不显示拒绝 —— 后端对 PROCESSING 的
                  REJECT 会判 409，前端不该给出一个必然失败的按钮。
                -->
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
            </tr>
          </template>
        </tbody>
      </table>

      <div v-else class="empty-box">
        暂无相关资料数据。
      </div>
    </div>

    <DepartureFormDialog
      v-if="resource === 'departures'"
      v-model="departureDialogVisible"
      :departure="editingDeparture"
      @saved="load"
    />
  </div>
</template>

<style scoped>
.amount {
  color: var(--price-orange);
  font-weight: 800;
}

.status-select {
  padding: 3px 6px;
  border: 1px solid var(--border-strong);
  border-radius: 6px;
  background: white;
  font-size: 12px;
  color: var(--text-primary);
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
</style>
