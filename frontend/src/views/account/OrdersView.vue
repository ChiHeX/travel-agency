<script setup>
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { orderApi } from '@/api/modules'

const router = useRouter()
const orders = ref([])
const loading = ref(false)
const activeStatus = ref('')

const labels = {
  WAIT_PAY: '待支付',
  PAID_WAIT_CONFIRM: '待确认',
  CONFIRMED: '已确认',
  TRAVELLING: '行程中',
  COMPLETED: '已完成',
  CANCELLED: '已取消',
  REFUND_APPLYING: '退款审核中',
  REFUND_PROCESSING: '退款处理中',
  REFUNDED: '已退款',
  REFUND_REJECTED: '退款未通过'
}

const statusTags = {
  WAIT_PAY: 'warning',
  PAID_WAIT_CONFIRM: 'warning',
  REFUND_APPLYING: 'warning',
  REFUND_PROCESSING: 'warning',
  CONFIRMED: 'success',
  TRAVELLING: 'success',
  COMPLETED: 'success',
  CANCELLED: 'danger',
  REFUNDED: 'danger',
  REFUND_REJECTED: 'danger'
}

async function load() {
  loading.value = true
  try {
    orders.value = (await orderApi.list(activeStatus.value ? { status: activeStatus.value } : {}))?.items || []
  } finally {
    loading.value = false
  }
}

async function cancel(order) {
  await ElMessageBox.confirm('确认取消这个待支付订单吗？已支付订单需要申请退款。', '取消订单', {
    type: 'warning',
    confirmButtonText: '确认取消',
    cancelButtonText: '再想想'
  })
  await orderApi.cancel(order.orderNo)
  ElMessage.success('订单已成功取消')
  load()
}

async function pay(order) {
  const data = await orderApi.pay(order.orderNo)
  if (import.meta.env.MODE === 'mock') {
    ElMessage.info('模拟支付请求已创建；真实环境将跳转至支付宝沙箱。')
    return
  }
  window.location.assign(data.paymentUrl)
}

function viewDetail(order) {
  router.push({ name: 'order-detail', params: { orderNo: order.orderNo } })
}

onMounted(load)
</script>

<template>
  <div class="account-page">
    <div class="container narrow-container page-section">
      <div class="section-head">
        <div>
          <span class="eyebrow">YOUR JOURNEYS</span>
          <h2>我的跟团订单</h2>
          <p>从下单、付款到出团履约，全程跟踪订单实时动态。</p>
        </div>
      </div>

      <!-- Segmented Status Tabs -->
      <div class="status-tab-bar">
        <button
          type="button"
          class="status-tab-btn"
          :class="{ active: !activeStatus }"
          @click="activeStatus = ''; load()"
        >
          全部订单
        </button>
        <button
          v-for="status in ['WAIT_PAY', 'PAID_WAIT_CONFIRM', 'CONFIRMED', 'TRAVELLING', 'COMPLETED', 'REFUND_APPLYING', 'REFUNDED']"
          :key="status"
          type="button"
          class="status-tab-btn"
          :class="{ active: activeStatus === status }"
          @click="activeStatus = status; load()"
        >
          {{ labels[status] }}
        </button>
      </div>

      <!-- Orders List Feed -->
      <div class="orders-feed">
        <div v-if="loading" class="orders-skeleton">
          <el-skeleton v-for="i in 3" :key="i" :rows="4" animated style="margin-bottom: 16px;" />
        </div>

        <div v-else-if="orders.length" class="orders-list">
          <article v-for="order in orders" :key="order.id" class="order-card-box">
            <div class="order-card-header">
              <div class="order-no-meta">
                <span class="order-no">订单号：{{ order.orderNo }}</span>
                <span class="order-created">{{ order.createdAt }}</span>
              </div>
              <span class="tag" :class="statusTags[order.status]">
                {{ labels[order.status] || order.status }}
              </span>
            </div>

            <div class="order-card-body" @click="viewDetail(order)">
              <div class="order-info-col">
                <h3 class="order-route-title">线路编号 #{{ order.routeId }}</h3>
                <div class="order-meta-chips">
                  <span>团期 #{{ order.departureId }}</span>
                  <span>·</span>
                  <span>联系人：{{ order.contactName }} ({{ order.contactPhone }})</span>
                  <span>·</span>
                  <span>共 {{ Number(order.adultCount || 0) + Number(order.childCount || 0) }} 位出行人</span>
                </div>
              </div>

              <div class="order-price-col">
                <span class="price-label">订单金额</span>
                <div class="price-amount">
                  <span class="curr">¥</span>
                  <strong>{{ order.totalAmount }}</strong>
                </div>
              </div>
            </div>

            <div class="order-card-footer">
              <span class="payment-badge">
                <span class="dot"></span>
                支付状态：{{ order.paymentStatus === 'PAID' ? '已支付' : '待支付 / 未支付' }}
              </span>

              <div class="order-actions">
                <button type="button" class="secondary-button action-btn" @click="viewDetail(order)">
                  订单详情
                </button>
                <button
                  v-if="order.status === 'WAIT_PAY'"
                  type="button"
                  class="primary-button action-btn"
                  @click="pay(order)"
                >
                  去支付
                </button>
                <button
                  v-if="order.status === 'WAIT_PAY'"
                  type="button"
                  class="danger-button action-btn"
                  @click="cancel(order)"
                >
                  取消订单
                </button>
              </div>
            </div>
          </article>
        </div>

        <div v-else class="empty-box">
          暂无相关订单记录，您可以前往跟团线路列表探索心仪行程。
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

.status-tab-bar {
  display: flex;
  align-items: center;
  gap: 6px;
  overflow-x: auto;
  padding-bottom: 4px;
  margin-bottom: 20px;
  scrollbar-width: none;
}

.status-tab-bar::-webkit-scrollbar {
  display: none;
}

.status-tab-btn {
  padding: 6px 14px;
  border-radius: var(--radius-full);
  background: white;
  border: 1px solid var(--border-line);
  font-size: 13px;
  font-weight: 500;
  color: var(--text-secondary);
  cursor: pointer;
  white-space: nowrap;
  transition: all 0.15s ease;
}

.status-tab-btn:hover {
  background: var(--bg-subtle);
  color: var(--text-primary);
  border-color: var(--border-strong);
}

.status-tab-btn.active {
  background: var(--brand-blue);
  color: white;
  border-color: var(--brand-blue);
  font-weight: 600;
  box-shadow: 0 2px 6px rgba(0, 113, 227, 0.25);
}

.orders-list {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.order-card-box {
  background: white;
  border: 1px solid var(--border-line);
  border-radius: var(--radius-lg);
  overflow: hidden;
  box-shadow: var(--shadow-xs);
  transition: all 0.2s ease;
}

.order-card-box:hover {
  border-color: var(--border-strong);
  box-shadow: var(--shadow-sm);
}

.order-card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 14px 20px;
  background: var(--bg-subtle);
  border-bottom: 1px solid var(--border-line);
}

.order-no-meta {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 12px;
}

.order-no {
  font-weight: 700;
  color: var(--text-primary);
}

.order-created {
  color: var(--text-tertiary);
}

.order-card-body {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 20px;
  cursor: pointer;
}

.order-route-title {
  font-size: 16px;
  font-weight: 700;
  color: var(--text-primary);
  margin: 0 0 6px;
}

.order-meta-chips {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: var(--text-secondary);
}

.order-price-col {
  text-align: right;
}

.price-label {
  font-size: 11px;
  color: var(--text-tertiary);
  display: block;
}

.price-amount {
  color: var(--price-orange);
  display: flex;
  align-items: baseline;
}

.price-amount .curr {
  font-size: 13px;
  font-weight: 700;
}

.price-amount strong {
  font-size: 22px;
  font-weight: 800;
  letter-spacing: -0.02em;
  margin-left: 2px;
}

.order-card-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 14px 20px;
  border-top: 1px solid var(--border-line);
  background: #ffffff;
}

.payment-badge {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--text-secondary);
}

.payment-badge .dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--brand-blue);
}

.order-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.action-btn {
  min-height: 32px;
  padding: 0 14px;
  font-size: 12px;
  border-radius: var(--radius-sm);
}

@media (max-width: 640px) {
  .order-card-body {
    flex-direction: column;
    align-items: flex-start;
    gap: 12px;
  }
  .order-price-col {
    text-align: left;
  }
  .order-card-footer {
    flex-direction: column;
    align-items: flex-start;
    gap: 10px;
  }
  .order-actions {
    width: 100%;
    justify-content: flex-end;
  }
}
</style>
