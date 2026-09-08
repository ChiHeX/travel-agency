<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { orderApi } from '@/api/modules'
import { orderStatusLabels, paymentStatusLabels } from '@/utils/order'

const route = useRoute()
const router = useRouter()
const detail = ref(null)
const loading = ref(true)
const refreshing = ref(false)
const errorMessage = ref('')
let timer
let attempts = 0

const paymentStatus = computed(() => detail.value?.payment?.status || detail.value?.order?.paymentStatus)
const paid = computed(() =>
  paymentStatus.value === 'PAID' ||
  ['PAID_WAIT_CONFIRM', 'CONFIRMED', 'TRAVELLING', 'COMPLETED', 'REFUND_APPLYING', 'REFUND_PROCESSING', 'REFUNDED', 'REFUND_REJECTED'].includes(detail.value?.order?.status)
)
const failed = computed(() => ['FAILED', 'CLOSED'].includes(paymentStatus.value) || detail.value?.order?.status === 'CANCELLED')
const pending = computed(() => !paid.value && !failed.value)

async function load({ initial = false } = {}) {
  if (initial) loading.value = true
  else refreshing.value = true
  errorMessage.value = ''
  try {
    detail.value = await orderApi.detail(route.params.orderNo)
  } catch (error) {
    errorMessage.value = error.message || '支付状态查询失败'
  } finally {
    loading.value = false
    refreshing.value = false
  }
}

async function poll() {
  await load()
  attempts += 1
  if (pending.value && attempts < 20) timer = window.setTimeout(poll, 3000)
}

onMounted(async () => {
  await load({ initial: true })
  if (pending.value) timer = window.setTimeout(poll, 3000)
})
onBeforeUnmount(() => window.clearTimeout(timer))
</script>

<template>
  <div class="result-page">
    <div class="container result-container page-section">
      <div class="result-card">
        <el-skeleton v-if="loading" :rows="6" animated />

        <template v-else-if="detail">
          <div class="result-icon" :class="{ success: paid, failed, pending }">
            {{ paid ? '✓' : failed ? '×' : '···' }}
          </div>
          <span class="eyebrow">SERVER VERIFIED STATUS</span>
          <h1>{{ paid ? '支付已确认' : failed ? '支付未完成' : '正在确认支付结果' }}</h1>
          <p v-if="paid">服务端已确认支付宝沙箱回调，订单将进入旅行社确认流程。</p>
          <p v-else-if="failed">当前支付未成功，您可以返回订单后重新发起沙箱支付。</p>
          <p v-else>页面正在向服务端查询订单状态。支付宝页面的跳转结果不会被直接作为支付成功依据。</p>

          <dl class="result-details">
            <div><dt>订单号</dt><dd>{{ detail.order.orderNo }}</dd></div>
            <div><dt>线路</dt><dd>{{ detail.route.name }}</dd></div>
            <div><dt>订单状态</dt><dd>{{ orderStatusLabels[detail.order.status] || detail.order.status }}</dd></div>
            <div><dt>支付状态</dt><dd>{{ paymentStatusLabels[paymentStatus] || paymentStatus }}</dd></div>
            <div v-if="detail.payment?.paymentNo"><dt>支付单号</dt><dd>{{ detail.payment.paymentNo }}</dd></div>
            <div v-if="detail.payment?.paidAt"><dt>支付时间</dt><dd>{{ detail.payment.paidAt }}</dd></div>
          </dl>

          <div v-if="errorMessage" class="query-error">{{ errorMessage }}</div>
          <div class="result-actions">
            <button v-if="pending" type="button" class="secondary-button" :disabled="refreshing" @click="load()">{{ refreshing ? '查询中...' : '立即刷新状态' }}</button>
            <button v-if="failed && detail.order.status === 'WAIT_PAY'" type="button" class="secondary-button" @click="router.push({ name: 'order-payment', params: { orderNo: detail.order.orderNo } })">重新支付</button>
            <button type="button" class="primary-button" @click="router.push({ name: 'order-detail', params: { orderNo: detail.order.orderNo } })">查看订单详情</button>
          </div>
        </template>

        <template v-else>
          <div class="result-icon failed">!</div>
          <h1>暂时无法查询支付结果</h1>
          <p>{{ errorMessage }}</p>
          <button type="button" class="secondary-button" @click="load({ initial: true })">重新查询</button>
        </template>
      </div>
    </div>
  </div>
</template>

<style scoped>
.result-page { min-height: calc(100vh - 64px); background: var(--bg-canvas); }
.result-container { max-width: 680px; }
.result-card { display: grid; justify-items: center; padding: 38px; border: 1px solid var(--border-line); border-radius: var(--radius-xl); background: #fff; box-shadow: var(--shadow-sm); text-align: center; }
.result-icon { display: grid; width: 58px; height: 58px; margin-bottom: 15px; place-items: center; border-radius: 50%; font-size: 25px; font-weight: 800; }
.result-icon.success { background: #eaf8ef; color: #228b4d; }
.result-icon.failed { background: var(--status-red-bg); color: var(--danger-red); }
.result-icon.pending { background: var(--brand-blue-subtle); color: var(--brand-blue); }
.result-card h1 { margin: 5px 0 8px; color: var(--text-primary); font-size: 25px; }
.result-card > p { max-width: 520px; margin: 0; color: var(--text-secondary); font-size: 13px; line-height: 1.65; }
.result-details { width: 100%; margin: 24px 0; border-top: 1px solid var(--border-line); }
.result-details div { display: grid; grid-template-columns: 110px 1fr; gap: 14px; padding: 11px 2px; border-bottom: 1px solid var(--border-line); text-align: left; }
.result-details dt { color: var(--text-tertiary); font-size: 12px; }
.result-details dd { margin: 0; color: var(--text-primary); font-size: 12px; font-weight: 600; word-break: break-all; }
.query-error { margin-bottom: 12px; color: var(--danger-red); font-size: 12px; }
.result-actions { display: flex; gap: 10px; }
@media (max-width: 600px) { .result-card { padding: 24px 18px; } .result-details div { grid-template-columns: 90px 1fr; } .result-actions { width: 100%; flex-direction: column; } }
</style>
