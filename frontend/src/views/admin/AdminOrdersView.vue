<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { adminApi } from '@/api/modules'

const rows = ref([])
const total = ref(0)
const page = ref(1)
const status = ref('')
const loading = ref(false)

const names = {
  WAIT_PAY: '待支付',
  PAID_WAIT_CONFIRM: '待确认',
  CONFIRMED: '已确认',
  TRAVELLING: '行程中',
  COMPLETED: '已完成',
  CANCELLED: '已取消',
  REFUND_APPLYING: '退款审核中',
  REFUNDED: '已退款'
}

async function load() {
  loading.value = true
  try {
    const data = await adminApi.orders({
      page: page.value,
      size: 20,
      status: status.value || undefined
    })
    rows.value = data?.items || []
    total.value = data?.total || 0
  } finally {
    loading.value = false
  }
}

async function confirm(row) {
  await ElMessageBox.confirm('确认审核通过该订单吗？系统会再次校验团期名额与出行人资料。', '确认报名', {
    type: 'warning',
    confirmButtonText: '审核通过',
    cancelButtonText: '取消'
  })
  await adminApi.confirmOrder(row.orderNo)
  ElMessage.success('订单已确认通过')
  load()
}

onMounted(load)
</script>

<template>
  <div class="admin-orders-page">
    <div class="admin-page-head">
      <div>
        <h2>报名订单管理</h2>
        <p>跟团游支付成功后需工作人员人工二次核实，避免超员或行程冲突直接进入出团流程。</p>
      </div>
    </div>

    <div class="admin-panel">
      <div class="toolbar">
        <select v-model="status" class="filter-select" @change="page = 1; load()">
          <option value="">全部订单状态</option>
          <option value="PAID_WAIT_CONFIRM">待工作人员确认 (PAID_WAIT_CONFIRM)</option>
          <option value="CONFIRMED">已确认出团 (CONFIRMED)</option>
          <option value="TRAVELLING">行程中 (TRAVELLING)</option>
          <option value="COMPLETED">已完成履约 (COMPLETED)</option>
          <option value="REFUND_APPLYING">申请退款中 (REFUND_APPLYING)</option>
        </select>
      </div>

      <div v-if="loading">
        <el-skeleton :rows="9" animated />
      </div>

      <table v-else-if="rows.length" class="data-table">
        <thead>
          <tr>
            <th>订单编号</th>
            <th>用户 ID</th>
            <th>线路 / 团期</th>
            <th>出行人数</th>
            <th>订单总额</th>
            <th>支付状态</th>
            <th>业务状态</th>
            <th style="text-align: right;">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in rows" :key="row.id">
            <td><strong>{{ row.orderNo }}</strong></td>
            <td>{{ row.userId }}</td>
            <td>线路 #{{ row.routeId }} / 团期 #{{ row.departureId }}</td>
            <td>{{ Number(row.adultCount || 0) + Number(row.childCount || 0) }} 人</td>
            <td class="amount">¥{{ row.totalAmount }}</td>
            <td>
              <span class="tag" :class="row.paymentStatus === 'PAID' ? 'success' : 'warning'">
                {{ row.paymentStatus === 'PAID' ? '已支付' : '待支付' }}
              </span>
            </td>
            <td>
              <span
                class="tag"
                :class="
                  row.status === 'CONFIRMED' || row.status === 'COMPLETED'
                    ? 'success'
                    : row.status === 'PAID_WAIT_CONFIRM'
                    ? 'warning'
                    : row.status === 'REFUNDED'
                    ? 'danger'
                    : ''
                "
              >
                {{ names[row.status] || row.status }}
              </span>
            </td>
            <td style="text-align: right;">
              <button
                v-if="row.status === 'PAID_WAIT_CONFIRM'"
                type="button"
                class="primary-button small-btn"
                @click="confirm(row)"
              >
                确认报名
              </button>
              <span v-else class="muted-text">—</span>
            </td>
          </tr>
        </tbody>
      </table>

      <div v-else class="empty-box">
        暂无符合筛选条件的订单数据。
      </div>

      <div v-if="total > 20" class="pagination-wrap">
        <el-pagination
          v-model:current-page="page"
          background
          layout="prev, pager, next"
          :page-size="20"
          :total="total"
          @current-change="load"
        />
      </div>
    </div>
  </div>
</template>

<style scoped>
.toolbar {
  margin-bottom: 20px;
}

.amount {
  color: var(--price-orange);
  font-weight: 800;
}

.muted-text {
  color: var(--text-tertiary);
  font-size: 12px;
}

.small-btn {
  min-height: 28px;
  padding: 0 10px;
  font-size: 11px;
}

.pagination-wrap {
  display: flex;
  justify-content: center;
  margin-top: 24px;
}
</style>
