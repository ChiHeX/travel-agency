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
  <div class="checkout">
    <header class="checkout-heading">
      <h1>确认并付款</h1>
    </header>

    <div v-if="loading" class="checkout-loading" aria-busy="true" aria-label="正在加载订单"><el-skeleton :rows="8" animated /></div>
    <section v-else-if="errorMessage" class="checkout-error" role="alert">
      <h2>暂时无法载入订单</h2><p>{{ errorMessage }}</p>
      <button class="pay-button" type="button" @click="load">重新加载</button>
    </section>

    <div v-else-if="detail" class="checkout-grid">
      <div class="checkout-information">
        <section class="method-section" aria-label="付款方式">
          <label class="payment-method">
            <input type="radio" name="payment-method" value="alipay" checked aria-label="支付宝沙箱" />
            <span class="method-copy"><strong>支付宝</strong><small>将在支付宝页面完成付款</small></span>
            <span class="sandbox-label">沙箱</span>
          </label>
        </section>
        <section class="trip-section" aria-labelledby="trip-heading">
          <h2 id="trip-heading">你的行程</h2>
          <h3>{{ detail.order.routeName || detail.route.name }}</h3>
          <dl class="trip-facts">
            <div><dt>出行日期</dt><dd>{{ detail.departure.startDate }} <span>至</span> {{ detail.departure.endDate }}</dd></div>
            <div><dt>出行人数</dt><dd>{{ detail.order.adultCount }} 位成人<span v-if="detail.order.childCount"> · {{ detail.order.childCount }} 位儿童</span></dd></div>
            <div><dt>订单编号</dt><dd class="order-number">{{ detail.order.orderNo }}</dd></div>
          </dl>
        </section>

        <section aria-labelledby="contact-heading">
          <h2 id="contact-heading">联系人信息</h2>
          <div class="contact-grid">
            <div class="contact-field"><span>姓名</span><p>{{ detail.order.contactName }}</p></div>
            <div class="contact-field"><span>手机号码</span><p>{{ detail.order.contactPhone }}</p></div>
            <div v-if="detail.order.contactEmail" class="contact-field contact-email"><span>电子邮箱</span><p>{{ detail.order.contactEmail }}</p></div>
          </div>
          <p class="section-note">行程通知将通过以上联系方式与你联系。</p>
        </section>

      </div>

      <aside class="payment-summary" aria-labelledby="summary-heading">
        <h2 id="summary-heading">订单摘要</h2>
        <p class="summary-route">{{ detail.order.routeName || detail.route.name }}</p>
        <dl class="price-lines">
          <div v-if="detail.order.adultCount"><dt>成人 × {{ detail.order.adultCount }}</dt><dd>¥{{ detail.order.adultUnitPrice }} <span>/ 人</span></dd></div>
          <div v-if="detail.order.childCount"><dt>儿童 × {{ detail.order.childCount }}</dt><dd>¥{{ detail.order.childUnitPrice }} <span>/ 人</span></dd></div>
        </dl>
        <div class="summary-total"><span>{{ canPay ? '本次应付' : '订单金额' }}</span><strong><small>¥</small>{{ detail.order.totalAmount }}</strong></div>
        <p class="payment-note">当前为支付宝沙箱测试支付，不涉及真实资金。</p>
        <template v-if="canPay">
          <p class="after-payment-note">确认行程与联系人信息后，即可前往支付宝付款。付款完成后，等待旅行社确认报名。</p>
          <button type="button" class="pay-button" :disabled="paying" @click="startPayment">{{ paying ? '正在前往支付宝…' : '前往支付宝付款' }}</button>
          <RouterLink class="later-link" :to="{ name: 'order-detail', params: { orderNo: detail.order.orderNo } }">稍后付款，查看订单</RouterLink>
        </template>
        <div v-else class="finished-state" role="status">
          <p>订单{{ orderStatusLabels[detail.order.status] || detail.order.status }} · {{ paymentStatusLabels[detail.order.paymentStatus] || detail.order.paymentStatus }}，当前无需付款。</p>
          <button class="pay-button" type="button" @click="router.push({ name: 'order-detail', params: { orderNo: detail.order.orderNo } })">查看订单详情</button>
        </div>
      </aside>
    </div>
  </div>
</template>

<style scoped>
.checkout-heading { margin-bottom: 40px; }
.checkout-heading h1 { margin: 0; font-size: 28px; font-weight: 650; line-height: 1.4; letter-spacing: -.03em; }
.checkout-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); align-items: start; gap: 58px; }
.checkout-information { display: flex; flex-direction: column; gap: 48px; }
h2 { margin: 0 0 20px; font-size: 17px; font-weight: 650; }
.method-section { order: 0; }
.trip-section { order: 2; }
.payment-method { position: relative; display: flex; flex-direction: column; align-items: flex-start; gap: 20px; min-height: 174px; padding: 24px; border: 1px solid var(--theme-blue); border-radius: 14px; background: var(--theme-blue-tint); cursor: pointer; }
.payment-method input { width: 26px; height: 26px; margin: 0; accent-color: var(--theme-blue); }
.method-copy { display: grid; gap: 8px; }.method-copy strong { font-size: 17px; font-weight: 550; }.method-copy small { font-size: 15px; color: var(--text-secondary); line-height: 1.5; }
.sandbox-label { position: absolute; top: 24px; right: 24px; padding: 4px 10px; border-radius: 6px; background: var(--theme-blue-tint); color: var(--theme-blue); font-size: 12px; }
.contact-grid { display: grid; gap: 20px; }
.contact-field { min-width: 0; }.contact-field > span { font-size: 15px; color: var(--text-secondary); }
.contact-field p { min-height: 40px; display: flex; align-items: center; margin: 8px 0 0; padding: 8px 12px; border: 1px solid var(--border-divider); border-radius: 9px; background: var(--app-bg); font-size: 15px; overflow-wrap: anywhere; }
.section-note { margin: 14px 0 0; color: var(--text-secondary); font-size: 13px; line-height: 1.6; }
.trip-section h3 { margin: 0 0 20px; font-size: 18px; font-weight: 550; line-height: 1.5; }
.trip-facts { display: grid; gap: 16px; margin: 0; }
.trip-facts > div { display: grid; grid-template-columns: 78px minmax(0, 1fr); gap: 16px; font-size: 14px; line-height: 1.5; }
dt { color: var(--text-secondary); } dd { margin: 0; overflow-wrap: anywhere; }
.trip-facts dd > span { color: var(--text-secondary); }
.order-number { font-variant-numeric: tabular-nums; font-size: 13px; }
.payment-summary { position: sticky; top: 32px; padding: 40px; border: 1px solid var(--border-divider); border-radius: 30px; background: var(--app-bg); box-shadow: var(--shadow-card); }
.payment-summary h2 { font-size: 30px; font-weight: 450; line-height: 1.4; letter-spacing: .02em; margin-bottom: 32px; }
.summary-route { color: var(--text-secondary); font-size: 16px; line-height: 1.6; margin: 0 0 20px; overflow-wrap: anywhere; }
.price-lines { display: grid; gap: 14px; margin: 0 0 22px; }.price-lines > div { display: flex; justify-content: space-between; gap: 16px; color: var(--text-secondary); font-size: 16px; line-height: 1.5; }.price-lines dd { text-align: right; font-variant-numeric: tabular-nums; }.price-lines dd span { font-size: 13px; }
.summary-total { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-bottom: 28px; font-size: 16px; line-height: 1.5; }.summary-total strong { font-size: 18px; font-weight: 600; font-variant-numeric: tabular-nums; }.summary-total small { font-size: inherit; margin-right: 3px; }
.payment-note { margin: 0 0 28px; padding: 18px 20px; border: 1px solid var(--border-divider); border-radius: 16px; color: var(--text-secondary); font-size: 14px; line-height: 1.7; background: var(--bg-secondary); }
.after-payment-note { margin: 0 0 26px; color: var(--text-secondary); font-size: 15px; line-height: 1.8; }
.pay-button { width: 100%; min-height: 48px; border: 1px solid var(--theme-blue); border-radius: 12px; padding: 12px 18px; background: var(--theme-blue); color: #fff; font: inherit; font-size: 16px; font-weight: 550; cursor: pointer; transition: background .15s; }.pay-button:hover { background: var(--theme-blue-hover); }.pay-button:disabled { opacity: .5; cursor: wait; }.pay-button:focus-visible, .later-link:focus-visible { outline: 3px solid var(--theme-blue); outline-offset: 3px; }
.later-link { display: block; margin-top: 24px; color: var(--text-secondary); font-size: 13px; text-align: center; text-decoration: underline; text-underline-offset: 4px; }
.finished-state p { color: var(--text-secondary); font-size: 15px; line-height: 1.8; margin: 0 0 26px; }
.checkout-loading { max-width: 560px; padding-top: 24px; }.checkout-error { max-width: 460px; padding: 32px; border: 1px solid var(--border-divider); border-radius: 16px; background: var(--app-bg); }.checkout-error p { color: var(--text-secondary); line-height: 1.7; margin-bottom: 24px; }
@media (max-width: 1000px) { .checkout-grid { gap: 32px; }.payment-summary { padding: 28px; } }
@media (max-width: 800px) { .checkout-grid { grid-template-columns: 1fr; gap: 36px; }.checkout-heading { margin-bottom: 30px; }.checkout-heading h1 { font-size: 26px; }.checkout-information { gap: 32px; }.payment-summary { position: static; padding: 28px 24px; border-radius: 24px; }.payment-summary h2 { font-size: 26px; }.payment-method { min-height: 160px; } }
</style>
