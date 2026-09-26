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
      <p>再核对一下行程，即可前往支付宝完成付款。</p>
    </header>

    <div v-if="loading" class="checkout-loading" aria-busy="true" aria-label="正在加载订单"><el-skeleton :rows="8" animated /></div>
    <section v-else-if="errorMessage" class="checkout-error" role="alert">
      <h2>暂时无法载入订单</h2><p>{{ errorMessage }}</p>
      <button class="pay-button" type="button" @click="load">重新加载</button>
    </section>

    <div v-else-if="detail" class="checkout-grid">
      <div class="checkout-information">
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

        <section aria-labelledby="method-heading">
          <h2 id="method-heading">付款方式</h2>
          <label class="payment-method">
            <input type="radio" name="payment-method" value="alipay" checked aria-label="支付宝沙箱" />
            <span class="method-copy"><strong>支付宝</strong><small>将在支付宝页面完成付款</small></span>
            <span class="sandbox-label">沙箱</span>
          </label>
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
          <button type="button" class="pay-button" :disabled="paying" @click="startPayment">{{ paying ? '正在前往支付宝…' : '前往支付宝付款' }}</button>
          <p class="after-payment-note">付款完成后，等待旅行社确认报名。</p>
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
.checkout-heading { margin-bottom: 38px; }
.checkout-heading h1 { margin: 0 0 12px; font-size: 30px; font-weight: 650; letter-spacing: -.04em; }
.checkout-heading p { margin: 0; color: #77766f; font-size: 14px; line-height: 1.7; }
.checkout-grid { display: grid; grid-template-columns: minmax(0, 1.1fr) minmax(0, 1fr); align-items: start; gap: 72px; }
.checkout-information { display: grid; gap: 40px; }
h2 { margin: 0 0 20px; font-size: 16px; font-weight: 650; }
.trip-section h3 { margin: 0 0 24px; font-size: 24px; font-weight: 550; letter-spacing: -.03em; line-height: 1.45; }
.trip-facts { display: grid; gap: 16px; margin: 0; }
.trip-facts > div { display: grid; grid-template-columns: 78px minmax(0, 1fr); gap: 16px; font-size: 14px; line-height: 1.5; }
dt { color: #77766f; } dd { margin: 0; overflow-wrap: anywhere; }
.trip-facts dd > span { color: #77766f; }
.order-number { font-variant-numeric: tabular-nums; font-size: 13px; }
.contact-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 18px; }
.contact-field { min-width: 0; }.contact-field > span { font-size: 13px; color: #77766f; }
.contact-field p { min-height: 44px; display: flex; align-items: center; margin: 8px 0 0; padding: 10px 14px; border: 1px solid #e5e3df; border-radius: 8px; background: #fff; font-size: 14px; overflow-wrap: anywhere; }
.contact-email { grid-column: 1 / -1; }
.section-note { margin: 12px 0 0; color: #89877f; font-size: 12px; line-height: 1.6; }
.payment-method { display: flex; align-items: center; gap: 14px; padding: 21px; border: 1px solid #8ab9ec; border-radius: 12px; background: #f0f6fc; cursor: pointer; }
.payment-method input { width: 18px; height: 18px; margin: 0; accent-color: #0071e3; flex-shrink: 0; }
.method-copy { display: grid; flex: 1; gap: 6px; }.method-copy strong { font-size: 15px; font-weight: 600; }.method-copy small { font-size: 12px; color: #757b82; }
.sandbox-label { padding: 3px 8px; border-radius: 5px; background: #dfebf7; color: #476989; font-size: 11px; }
.payment-summary { position: sticky; top: 32px; padding: 36px; border: 1px solid #dfddd8; border-radius: 22px; background: #fff; box-shadow: 0 4px 18px rgba(30,30,25,.06), 0 1px 3px rgba(30,30,25,.04); }
.payment-summary h2 { font-size: 23px; font-weight: 550; letter-spacing: -.03em; margin-bottom: 16px; }
.summary-route { color: #77766f; font-size: 14px; line-height: 1.6; margin: 0 0 30px; }
.price-lines { display: grid; gap: 16px; margin: 0 0 24px; }.price-lines > div { display: flex; justify-content: space-between; gap: 16px; font-size: 14px; }.price-lines dd { text-align: right; font-variant-numeric: tabular-nums; }.price-lines dd span { color: #89877f; font-size: 12px; }
.summary-total { display: flex; align-items: center; justify-content: space-between; gap: 16px; border-top: 1px solid #eceae6; padding-top: 24px; margin-bottom: 28px; font-size: 14px; }.summary-total strong { font-size: 30px; font-weight: 550; letter-spacing: -.04em; font-variant-numeric: tabular-nums; }.summary-total small { font-size: 20px; margin-right: 3px; }
.payment-note { margin: 0 0 24px; padding: 14px 16px; border: 1px solid #e8e6e2; border-radius: 10px; color: #77766f; font-size: 12px; line-height: 1.7; background: #fcfbf9; }
.pay-button { width: 100%; min-height: 46px; border: 1px solid #292925; border-radius: 9px; padding: 12px 18px; background: #292925; color: #fff; font: inherit; font-size: 14px; font-weight: 550; cursor: pointer; transition: background .15s; }.pay-button:hover { background: #44443c; }.pay-button:disabled { opacity: .5; cursor: wait; }.pay-button:focus-visible, .later-link:focus-visible { outline: 3px solid #8ab9ec; outline-offset: 3px; }
.after-payment-note { margin: 12px 0 22px; color: #89877f; font-size: 12px; line-height: 1.6; text-align: center; }
.later-link { display: block; color: #77766f; font-size: 13px; text-align: center; text-decoration: underline; text-underline-offset: 4px; }
.finished-state p { color: #77766f; font-size: 14px; line-height: 1.7; margin: 0 0 20px; }
.checkout-loading { max-width: 560px; padding-top: 24px; }.checkout-error { max-width: 460px; padding: 32px; border: 1px solid #e5e3df; border-radius: 16px; background: white; }.checkout-error p { color: #77766f; line-height: 1.7; margin-bottom: 24px; }
@media (max-width: 960px) { .checkout-grid { gap: 36px; }.payment-summary { padding: 28px; } }
@media (max-width: 700px) { .checkout-grid { grid-template-columns: 1fr; gap: 32px; }.checkout-heading { margin-bottom: 30px; }.checkout-heading h1 { font-size: 27px; }.checkout-information { gap: 32px; }.payment-summary { position: static; padding: 24px; border-radius: 16px; }.trip-section h3 { font-size: 21px; }.contact-grid { grid-template-columns: 1fr; gap: 14px; } }
</style>