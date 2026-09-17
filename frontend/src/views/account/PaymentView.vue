<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { orderApi } from '@/api/modules'
import { createIdempotencyKey, isSafePaymentUrl, orderStatusLabels, paymentStatusLabels } from '@/utils/order'

const route = useRoute()
const router = useRouter()
const detail = ref(null)
const loading = ref(true)
const paying = ref(false)
const errorMessage = ref('')
let paymentKey = createIdempotencyKey()

const canPay = computed(() => detail.value?.order?.status === 'WAIT_PAY')

async function load() {
  loading.value = true
  errorMessage.value = ''
  try {
    detail.value = await orderApi.detail(route.params.orderNo)
  } catch (error) {
    errorMessage.value = error.message || '订单信息加载失败'
  } finally {
    loading.value = false
  }
}

async function startPayment() {
  if (!canPay.value || paying.value) return
  paying.value = true
  try {
    const payment = await orderApi.pay(route.params.orderNo, paymentKey)
    if (!isSafePaymentUrl(payment?.paymentUrl)) {
      throw new Error('支付地址无效，请稍后重试')
    }
    paymentKey = createIdempotencyKey()
    window.location.assign(payment.paymentUrl)
  } catch (error) {
    ElMessage.error(error.message || '发起支付失败')
  } finally {
    paying.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="payment-page">
    <div class="container payment-container page-section">
      <div v-if="loading" class="payment-card"><el-skeleton :rows="7" animated /></div>

      <div v-else-if="errorMessage" class="payment-card payment-state">
        <span class="state-icon error">!</span>
        <h2>无法载入支付信息</h2>
        <p>{{ errorMessage }}</p>
        <button type="button" class="secondary-button" @click="load">重新加载</button>
      </div>

      <template v-else-if="detail">
        <div class="payment-card">
          <span class="eyebrow">ALIPAY SANDBOX</span>
          <h1>确认支付</h1>
          <p class="lead">核对订单后，将跳转至支付宝沙箱完成课程项目测试支付。</p>

          <div class="order-summary">
            <div>
              <span>跟团线路</span>
              <strong>{{ detail.route.name }}</strong>
            </div>
            <div>
              <span>出发日期</span>
              <strong>{{ detail.departure.startDate }}</strong>
            </div>
            <div>
              <span>出行人数</span>
              <strong>{{ detail.order.adultCount }} 成人 · {{ detail.order.childCount }} 儿童</strong>
            </div>
            <div>
              <span>订单号</span>
              <strong>{{ detail.order.orderNo }}</strong>
            </div>
          </div>

          <div class="pay-total">
            <div>
              <span>订单金额</span>
              <small>价格与出行人信息已按下单时快照保存</small>
            </div>
            <strong>¥{{ detail.order.totalAmount }}</strong>
          </div>

          <div class="sandbox-note">
            本页面仅发起支付宝沙箱支付，不处理真实资金。最终支付状态以服务端验签回调为准。
          </div>

          <div v-if="canPay" class="payment-actions">
            <button type="button" class="secondary-button" @click="router.push({ name: 'order-detail', params: { orderNo: detail.order.orderNo } })">稍后支付</button>
            <button type="button" class="primary-button" :disabled="paying" @click="startPayment">
              {{ paying ? '正在创建支付...' : `支付宝沙箱支付 ¥${detail.order.totalAmount}` }}
            </button>
          </div>
          <div v-else class="payment-finished">
            当前订单为“{{ orderStatusLabels[detail.order.status] || detail.order.status }}”，支付状态为“{{ paymentStatusLabels[detail.order.paymentStatus] || detail.order.paymentStatus }}”，无需再次发起支付。
            <button type="button" class="primary-button" @click="router.push({ name: 'order-detail', params: { orderNo: detail.order.orderNo } })">查看订单详情</button>
          </div>
        </div>
      </template>
    </div>
  </div>
</template>

<style scoped>
.payment-page { min-height: calc(100vh - 64px); background: var(--bg-canvas); }
.payment-container { max-width: 720px; }
.payment-card { padding: 34px; border: 1px solid var(--border-line); border-radius: var(--radius-xl); background: #fff; box-shadow: var(--shadow-sm); }
.payment-card h1 { margin: 5px 0 7px; color: var(--text-primary); font-size: 26px; }
.lead { margin: 0 0 26px; color: var(--text-secondary); font-size: 13px; }
.order-summary { display: grid; grid-template-columns: 1fr 1fr; gap: 1px; overflow: hidden; border: 1px solid var(--border-line); border-radius: var(--radius-md); background: var(--border-line); }
.order-summary > div { display: grid; gap: 4px; padding: 15px; background: var(--bg-subtle); }
.order-summary span, .pay-total span { color: var(--text-tertiary); font-size: 11px; }
.order-summary strong { color: var(--text-primary); font-size: 13px; }
.pay-total { display: flex; align-items: center; justify-content: space-between; margin-top: 20px; padding: 18px 0; border-bottom: 1px solid var(--border-line); }
.pay-total > div { display: grid; gap: 3px; }
.pay-total small { color: var(--text-tertiary); font-size: 11px; }
.pay-total > strong { color: var(--price-orange); font-size: 30px; }
.sandbox-note { margin: 18px 0; padding: 11px 13px; border-radius: var(--radius-sm); background: #fff8e8; color: #7a5200; font-size: 12px; line-height: 1.55; }
.payment-actions { display: flex; justify-content: flex-end; gap: 10px; }
.payment-finished, .payment-state { display: grid; justify-items: center; gap: 12px; color: var(--text-secondary); text-align: center; }
.payment-finished button { margin-top: 5px; }
.payment-state p { margin: 0; }
.state-icon { display: grid; width: 46px; height: 46px; place-items: center; border-radius: 50%; background: var(--status-red-bg); color: var(--danger-red); font-size: 22px; font-weight: 800; }
@media (max-width: 600px) { .payment-card { padding: 22px; } .order-summary { grid-template-columns: 1fr; } .payment-actions { flex-direction: column-reverse; } }
</style>
