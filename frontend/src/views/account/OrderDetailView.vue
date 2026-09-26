<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { orderApi } from '@/api/modules'
import CancelOrderButton from '@/components/CancelOrderButton.vue'
import {
  createIdempotencyKey,
  genderLabels,
  idTypeLabels,
  orderStatusLabels,
  paymentStatusLabels,
  refundStatusLabels
} from '@/utils/order'

const currentRoute = useRoute()
const router = useRouter()
const detail = ref(null)
const loading = ref(true)
const errorMessage = ref('')
const refundOpen = ref(false)
const reviewOpen = ref(false)
const refundSubmitting = ref(false)
const reviewSubmitting = ref(false)
const cancelling = ref(false)
const actionError = ref('')
const refund = reactive({ reason: '' })
const review = reactive({ rating: 5, content: '' })
let refundKey = createIdempotencyKey()

const labels = orderStatusLabels

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
  errorMessage.value = ''
  try {
    detail.value = await orderApi.detail(currentRoute.params.orderNo)
  } catch (error) {
    detail.value = null
    errorMessage.value = error.message || '订单详情加载失败'
  } finally {
    loading.value = false
  }
}

async function submitRefund() {
  if (refundSubmitting.value) return
  actionError.value = ''
  const reason = refund.reason.trim()
  if (reason.length < 2 || reason.length > 500) return ElMessage.warning('退款原因需填写 2–500 个字符')
  refundSubmitting.value = true
  try {
    await orderApi.refund(currentRoute.params.orderNo, { reason }, refundKey)
    refundKey = createIdempotencyKey()
    refund.reason = ''
    refundOpen.value = false
    ElMessage.success('退款申请已提交，请等待审核')
    await load()
  } catch (cause) {
    actionError.value = cause.message || '退款申请提交失败'
  } finally {
    refundSubmitting.value = false
  }
}

async function submitReview() {
  if (reviewSubmitting.value || !canReview.value) return
  actionError.value = ''
  const content = review.content.trim()
  if (!Number.isInteger(review.rating) || review.rating < 1 || review.rating > 5) return ElMessage.warning('请选择 1–5 星评分')
  if (!content || content.length > 1000) return ElMessage.warning('请填写 1–1000 个字符的评价内容')
  reviewSubmitting.value = true
  try {
    const saved = await orderApi.review(currentRoute.params.orderNo, { rating: review.rating, content })
    detail.value.review = saved
    reviewOpen.value = false
    ElMessage.success('感谢您的真实评价')
  } catch (cause) {
    actionError.value = cause.message || '评价提交失败'
    if (cause.status === 409) await load()
  } finally {
    reviewSubmitting.value = false
  }
}

async function cancelOrder() {
  try {
    cancelling.value = true
    await orderApi.cancel(currentRoute.params.orderNo)
    ElMessage.success('订单已取消')
    await load()
  } catch (error) {
    actionError.value = error.message || '取消失败，请重试'
  } finally {
    cancelling.value = false
  }
}

const canRefund = computed(() =>
  ['PAID_WAIT_CONFIRM', 'CONFIRMED'].includes(detail.value?.order?.status) &&
  !(detail.value?.refunds || []).some((item) => ['APPLYING', 'PROCESSING'].includes(item.status))
)

const canReview = computed(() => detail.value?.order?.status === 'COMPLETED' && !detail.value?.review)

onMounted(load)
</script>

<template>
  <div class="order-detail-page">
    <div class="order-content">
      <div v-if="loading" class="admin-panel">
        <el-skeleton :rows="9" animated />
      </div>

      <template v-else-if="detail">

        <!-- Order Hero Card -->
        <div class="order-hero-card">
          <div class="order-hero-top">
            <div>
              <p class="order-caption">订单详情</p>
              <h1>{{ detail.order.routeName || detail.route?.name || `跟团线路 #${detail.order.routeId}` }}</h1>
              <div class="order-id-meta">
                <span>订单号：{{ detail.order.orderNo }}</span>
                <span>·</span>
                <span>下单时间：{{ detail.order.createdAt }}</span>
              </div>
            </div>

            <span class="tag status-pill-lg" :class="statusTags[detail.order.status]">
              {{ labels[detail.order.status] || detail.order.status }}
            </span>
          </div>

          <!-- Summary Metric Cards Grid -->
          <div class="summary-tiles-grid">
            <div class="tile">
              <span class="tile-label">出行团期</span>
              <strong>{{ detail.departure?.startDate }} 至 {{ detail.departure?.endDate }}</strong>
            </div>
            <div class="tile">
              <span class="tile-label">联系人信息</span>
              <strong>{{ detail.order.contactName }} · {{ detail.order.contactPhone }}</strong>
              <small v-if="detail.order.contactEmail">{{ detail.order.contactEmail }}</small>
            </div>
            <div class="tile">
              <span class="tile-label">出行人数</span>
              <strong>{{ Number(detail.order.adultCount || 0) + Number(detail.order.childCount || 0) }} 位实名出行人</strong>
            </div>
            <div class="tile highlight">
              <span class="tile-label">订单金额</span>
              <strong class="price-val">¥{{ detail.order.totalAmount }}</strong>
            </div>
          </div>
        </div>

        <!-- Progress Timeline Card -->
        <div class="detail-card progress-card">
          <div class="card-head">
            <h3>行程进度</h3>
            <span class="sub-label">支付状态：{{ paymentStatusLabels[detail.order.paymentStatus] || detail.order.paymentStatus }}</span>
          </div>

          <div class="timeline-stepper">
            <div class="step" :class="{ completed: ['WAIT_PAY', 'PAID_WAIT_CONFIRM', 'CONFIRMED', 'TRAVELLING', 'COMPLETED'].includes(detail.order.status) }">
              <div class="step-icon">1</div>
              <span class="step-text">订单创建</span>
            </div>
            <div class="step-line" :class="{ active: ['PAID_WAIT_CONFIRM', 'CONFIRMED', 'TRAVELLING', 'COMPLETED'].includes(detail.order.status) }"></div>

            <div class="step" :class="{ completed: ['PAID_WAIT_CONFIRM', 'CONFIRMED', 'TRAVELLING', 'COMPLETED'].includes(detail.order.status) }">
              <div class="step-icon">2</div>
              <span class="step-text">支付成功</span>
            </div>
            <div class="step-line" :class="{ active: ['CONFIRMED', 'TRAVELLING', 'COMPLETED'].includes(detail.order.status) }"></div>

            <div class="step" :class="{ completed: ['CONFIRMED', 'TRAVELLING', 'COMPLETED'].includes(detail.order.status) }">
              <div class="step-icon">3</div>
              <span class="step-text">旅行社确认</span>
            </div>
            <div class="step-line" :class="{ active: ['TRAVELLING', 'COMPLETED'].includes(detail.order.status) }"></div>

            <div class="step" :class="{ completed: ['TRAVELLING', 'COMPLETED'].includes(detail.order.status) }">
              <div class="step-icon">4</div>
              <span class="step-text">行程中</span>
            </div>
            <div class="step-line" :class="{ active: detail.order.status === 'COMPLETED' }"></div>

            <div class="step" :class="{ completed: detail.order.status === 'COMPLETED' }">
              <div class="step-icon">5</div>
              <span class="step-text">行程完成</span>
            </div>
          </div>
        </div>

        <div class="detail-card payment-card">
          <div class="card-head">
            <h3>价格与支付记录</h3>
            <span class="sub-label">下单时价格</span>
          </div>
          <div class="payment-grid">
            <div><span>成人单价</span><strong>¥{{ detail.order.adultUnitPrice }}</strong></div>
            <div><span>儿童单价</span><strong>¥{{ detail.order.childUnitPrice }}</strong></div>
            <div><span>支付状态</span><strong>{{ paymentStatusLabels[detail.order.paymentStatus] || detail.order.paymentStatus }}</strong></div>
            <div><span>支付时间</span><strong>{{ detail.order.paidAt || '—' }}</strong></div>
            <template v-if="detail.payment">
              <div><span>支付单号</span><strong>{{ detail.payment.paymentNo }}</strong></div>
              <div><span>支付渠道</span><strong>{{ detail.payment.channel === 'ALIPAY_SANDBOX' ? '支付宝沙箱' : detail.payment.channel }}</strong></div>
              <div><span>支付金额</span><strong>¥{{ detail.payment.amount }}</strong></div>
              <div><span>第三方交易号</span><strong>{{ detail.payment.thirdPartyTradeNo || '—' }}</strong></div>
            </template>
          </div>
          <p v-if="detail.order.remark" class="order-remark"><span>订单备注</span>{{ detail.order.remark }}</p>
        </div>

        <!-- Traveler Snapshot Card -->
        <div class="detail-card travelers-card">
          <div class="card-head">
            <h3>出行人信息</h3>
            <span class="sub-label">证件号码已隐藏部分内容</span>
          </div>

          <table class="data-table responsive-cards">
            <thead>
              <tr>
                <th>出行人姓名</th>
                <th>性别</th>
                <th>证件类型与号码</th>
                <th>联系电话</th>
                <th>紧急联系人</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="traveler in detail.travelers" :key="traveler.id">
                <td data-label="姓名"><strong>{{ traveler.name }}</strong></td>
                <td data-label="性别">{{ genderLabels[traveler.gender] || traveler.gender }}</td>
                <td data-label="证件">{{ idTypeLabels[traveler.idType] || traveler.idType }} {{ traveler.idNoMasked }}</td>
                <td data-label="电话">{{ traveler.phone || '—' }}</td>
                <td data-label="紧急联系人">{{ traveler.emergencyName }} ({{ traveler.emergencyPhone || '—' }})</td>
              </tr>
            </tbody>
          </table>
        </div>

        <div v-if="detail.refunds.length" class="detail-card">
          <div class="card-head">
            <h3>退款记录</h3>
            <span class="sub-label">共 {{ detail.refunds.length }} 条</span>
          </div>
          <div class="refund-list">
            <article v-for="item in detail.refunds" :key="item.id" class="refund-item">
              <div class="refund-head">
                <strong>¥{{ item.amount }}</strong>
                <span class="tag" :class="item.status === 'REFUNDED' ? 'success' : item.status === 'REJECTED' ? 'danger' : 'warning'">
                  {{ refundStatusLabels[item.status] || item.status }}
                </span>
              </div>
              <dl>
                <div><dt>申请原因</dt><dd>{{ item.reason }}</dd></div>
                <div><dt>申请时间</dt><dd>{{ item.createdAt }}</dd></div>
                <div v-if="item.reviewedAt"><dt>审核时间</dt><dd>{{ item.reviewedAt }}</dd></div>
                <div v-if="item.reviewComment"><dt>审核意见</dt><dd>{{ item.reviewComment }}</dd></div>
              </dl>
            </article>
          </div>
        </div>

        <!-- Bottom Actions -->
        <div v-if="detail.review" class="detail-card">
          <h3>我的评价</h3>
          <el-rate :model-value="detail.review.rating" disabled />
          <p class="review-copy">{{ detail.review.content }}</p>
          <span class="sub-label">{{ detail.review.createdAt }}</span>
          <RouterLink :to="{ name: 'route-detail', params: { id: detail.order.routeId } }" class="text-button">查看线路评价</RouterLink>
        </div>
        <div class="bottom-actions-row">
          <p v-if="actionError && !reviewOpen && !refundOpen" class="form-error" role="alert">{{ actionError }}</p>
          <CancelOrderButton
            v-if="detail.order.status === 'WAIT_PAY'"
            size="large"
            :pending="cancelling"
            @confirm="cancelOrder"
          />
          <button
            v-if="canRefund"
            type="button"
            class="secondary-button danger-button"
            @click="refundOpen = true"
          >
            申请退款
          </button>
          <button
            v-if="canReview"
            type="button"
            class="primary-button"
            @click="reviewOpen = true"
          >
            评价本次行程
          </button>
          <button
            v-if="detail.order.status === 'WAIT_PAY'"
            type="button"
            class="primary-button"
            @click="router.push({ name: 'order-payment', params: { orderNo: detail.order.orderNo } })"
          >
            去支付
          </button>
        </div>
      </template>

      <div v-else class="empty-box detail-error">
        <strong>订单详情暂时无法加载</strong>
        <span>{{ errorMessage || '订单记录不存在或无权访问。' }}</span>
        <button type="button" class="secondary-button" @click="load">重新加载</button>
      </div>
    </div>

    <!-- Refund Dialog -->
    <el-dialog v-model="refundOpen" title="申请订单退款" width="min(460px, calc(100vw - 32px))" :close-on-click-modal="!refundSubmitting" :show-close="!refundSubmitting">
      <p v-if="actionError" class="form-error" role="alert">{{ actionError }}</p>
      <p>订单金额：¥{{ detail?.order.totalAmount }}，退款金额以审核结果为准。</p>
      <div class="form-field">
        <label>请填写详细退款原因</label>
        <textarea v-model="refund.reason" rows="4" placeholder="例如：时间冲突无法按期出行，申请办理退款手续..."></textarea>
      </div>
      <template #footer>
        <button class="secondary-button" @click="refundOpen = false">取消</button>
        <button class="primary-button" :disabled="refundSubmitting" @click="submitRefund">{{ refundSubmitting ? '提交中...' : '提交退款申请' }}</button>
      </template>
    </el-dialog>

    <!-- Review Dialog -->
    <el-dialog v-model="reviewOpen" title="评价跟团游体验" width="min(460px, calc(100vw - 32px))" :close-on-click-modal="!reviewSubmitting" :show-close="!reviewSubmitting">
      <p v-if="actionError" class="form-error" role="alert">{{ actionError }}</p>
      <p>评价提交后不可重复提交，请确认内容。</p>
      <div class="form-field">
        <label>整体评分</label>
        <el-rate v-model="review.rating" />
      </div>
      <div class="form-field">
        <label>行程体验与导游服务评价</label>
        <textarea v-model="review.content" rows="4" maxlength="1000" placeholder="分享本次线路体验、酒店餐饮及导游讲解..."></textarea>
      </div>
      <template #footer>
        <button class="secondary-button" @click="reviewOpen = false">取消</button>
        <button class="primary-button" :disabled="reviewSubmitting" @click="submitReview">{{ reviewSubmitting ? '发布中...' : '发布评价' }}</button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.order-content { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 24px; }
.order-hero-card, .progress-card, .payment-card, .travelers-card, .bottom-actions-row, .detail-error, .admin-panel { grid-column: 1 / -1; }
.order-hero-card { padding: 0 0 12px; }
.order-hero-top { display: flex; justify-content: space-between; align-items: flex-start; gap: 24px; margin-bottom: 32px; }
.order-caption { margin-bottom: 12px; color: var(--text-secondary); font-size: 14px; }
.order-hero-top h1 { font-size: 30px; font-weight: 650; line-height: 1.4; letter-spacing: -.03em; }
.order-id-meta { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 14px; color: var(--text-secondary); font-size: 13px; overflow-wrap: anywhere; }
.status-pill-lg { flex-shrink: 0; margin-top: 6px; padding: 8px 16px; font-size: 13px; }
.summary-tiles-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 24px; padding: 28px; background: var(--bg-secondary); border-radius: 20px; }
.tile { min-width: 0; }.tile-label { display: block; margin-bottom: 10px; color: var(--text-secondary); font-size: 13px; }
.tile strong { font-size: 15px; font-weight: 550; overflow-wrap: anywhere; }.tile small { display: block; margin-top: 6px; color: var(--text-secondary); overflow-wrap: anywhere; }
.tile.highlight .price-val { font-size: 24px; font-weight: 600; }
.detail-card { min-width: 0; padding: 28px; border: 1px solid var(--border-divider); border-radius: 20px; background: var(--app-bg); }
.card-head { display: flex; flex-wrap: wrap; justify-content: space-between; align-items: center; gap: 10px; margin-bottom: 24px; }
.detail-card h3 { font-size: 18px; font-weight: 600; }.sub-label { color: var(--text-secondary); font-size: 12px; }
.payment-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 24px; }
.payment-grid > div { display: grid; align-content: start; gap: 8px; }.payment-grid span, .order-remark span { color: var(--text-secondary); font-size: 13px; }
.payment-grid strong { font-size: 14px; font-weight: 500; overflow-wrap: anywhere; }
.order-remark { display: grid; gap: 8px; margin-top: 24px; padding: 16px; border-radius: 12px; background: var(--bg-secondary); line-height: 1.6; overflow-wrap: anywhere; }
.timeline-stepper { display: flex; align-items: center; justify-content: space-between; padding: 8px 0; }
.step { display: flex; flex-direction: column; align-items: center; gap: 10px; }
.step-icon { display: grid; place-items: center; width: 30px; height: 30px; border-radius: 50%; background: var(--bg-secondary); color: var(--text-secondary); font-size: 13px; }
.step.completed .step-icon { background: var(--theme-blue); color: white; }.step-text { color: var(--text-secondary); font-size: 13px; }.step.completed .step-text { color: var(--text-primary); }
.step-line { flex: 1; height: 2px; background: var(--border-divider); margin: 0 12px 28px; }.step-line.active { background: var(--theme-blue); }
.data-table { width: 100%; }.data-table th { background: var(--bg-secondary); font-weight: 500; }.data-table td { overflow-wrap: anywhere; }
.refund-list { display: grid; gap: 20px; }.refund-item + .refund-item { padding-top: 20px; border-top: 1px solid var(--border-divider); }
.refund-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; }.refund-head > strong { font-size: 20px; font-weight: 550; }
.refund-item dl { display: grid; gap: 12px; margin-top: 18px; }.refund-item dl div { display: grid; grid-template-columns: 72px minmax(0, 1fr); gap: 16px; font-size: 13px; }.refund-item dt { color: var(--text-secondary); }.refund-item dd { margin: 0; overflow-wrap: anywhere; line-height: 1.6; }
.review-copy { margin: 16px 0; line-height: 1.7; overflow-wrap: anywhere; }.text-button { display: block; margin-top: 18px; color: var(--text-link); }
.bottom-actions-row { display: flex; flex-wrap: wrap; align-items: center; justify-content: flex-end; gap: 12px; padding: 12px 0; }.bottom-actions-row .form-error { flex-basis: 100%; }
.bottom-actions-row button { min-height: 44px; padding: 0 24px; }.danger-button { color: var(--status-red); }
.detail-error { display: grid; justify-items: center; gap: 16px; padding: 48px 20px; }.detail-error span { color: var(--text-secondary); }
@media (max-width: 900px) { .payment-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } .order-content { grid-template-columns: 1fr; }.summary-tiles-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }.order-hero-top h1 { font-size: 26px; } }
@media (max-width: 600px) {
  .order-hero-top { flex-direction: column; gap: 16px; }.summary-tiles-grid { padding: 20px; gap: 24px 16px; }.detail-card { padding: 20px; }.order-hero-top h1 { font-size: 24px; }
  .timeline-stepper { gap: 12px; flex-wrap: wrap; justify-content: flex-start; }.step { min-width: 76px; }.step-line { display: none; }
  .responsive-cards, .responsive-cards tbody { display: block; width: 100%; }.responsive-cards thead { display: none; }.responsive-cards tr { display: block; padding: 12px 0; }.responsive-cards tr + tr { border-top: 1px solid var(--border-divider); }
  .responsive-cards td { display: flex; justify-content: space-between; gap: 16px; padding: 8px 0; border: 0; text-align: right; }.responsive-cards td::before { content: attr(data-label); flex: 0 0 76px; text-align: left; color: var(--text-secondary); }
  .bottom-actions-row button { flex: 1; }
}
</style>
