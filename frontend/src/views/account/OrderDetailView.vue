<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { orderApi } from '@/api/modules'
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
  } finally {
    refundSubmitting.value = false
  }
}

async function submitReview() {
  const content = review.content.trim()
  if (!content || content.length > 1000) return ElMessage.warning('请填写 1–1000 个字符的评价内容')
  reviewSubmitting.value = true
  try {
    await orderApi.review(currentRoute.params.orderNo, { rating: review.rating, content })
    reviewOpen.value = false
    ElMessage.success('感谢您的真实评价')
    await load()
  } finally {
    reviewSubmitting.value = false
  }
}

async function cancelOrder() {
  try {
    await ElMessageBox.confirm('取消后将释放本次占用的团期名额，是否继续？', '取消待支付订单', {
      type: 'warning',
      confirmButtonText: '确认取消',
      cancelButtonText: '保留订单'
    })
    cancelling.value = true
    await orderApi.cancel(currentRoute.params.orderNo)
    ElMessage.success('订单已取消')
    await load()
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') throw error
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
    <div class="container narrow-container page-section">
      <div v-if="loading" class="admin-panel">
        <el-skeleton :rows="9" animated />
      </div>

      <template v-else-if="detail">
        <!-- Back Navigation -->
        <RouterLink to="/account/orders" class="back-orders-btn">
          ← 返回订单列表
        </RouterLink>

        <!-- Order Hero Card -->
        <div class="order-hero-card">
          <div class="order-hero-top">
            <div>
              <span class="eyebrow">ORDER DETAILS</span>
              <h1>{{ detail.route?.name || `跟团线路 #${detail.order.routeId}` }}</h1>
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
        <div class="detail-card">
          <div class="card-head">
            <h3>履约时间线</h3>
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

        <div class="detail-card">
          <div class="card-head">
            <h3>价格与支付记录</h3>
            <span class="sub-label">下单价格快照</span>
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
        <div class="detail-card">
          <div class="card-head">
            <h3>出行人实名资料（快照保存）</h3>
            <span class="sub-label">历史订单不受后续修改影响 · 证件号已脱敏保护</span>
          </div>

          <table class="data-table">
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
                <td><strong>{{ traveler.name }}</strong></td>
                <td>{{ genderLabels[traveler.gender] || traveler.gender }}</td>
                <td>{{ idTypeLabels[traveler.idType] || traveler.idType }} {{ traveler.idNoMasked }}</td>
                <td>{{ traveler.phone || '—' }}</td>
                <td>{{ traveler.emergencyName }} ({{ traveler.emergencyPhone || '—' }})</td>
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
        <div class="bottom-actions-row">
          <button
            v-if="detail.order.status === 'WAIT_PAY'"
            type="button"
            class="secondary-button danger-button"
            :disabled="cancelling"
            @click="cancelOrder"
          >
            {{ cancelling ? '取消中...' : '取消订单' }}
          </button>
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
    <el-dialog v-model="refundOpen" title="申请订单退款" width="460px">
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
    <el-dialog v-model="reviewOpen" title="评价跟团游体验" width="460px">
      <div class="form-field">
        <label>整体评分</label>
        <el-rate v-model="review.rating" />
      </div>
      <div class="form-field">
        <label>行程体验与导游服务评价</label>
        <textarea v-model="review.content" rows="4" placeholder="分享本次线路体验、酒店餐饮及导游讲解..."></textarea>
      </div>
      <template #footer>
        <button class="secondary-button" @click="reviewOpen = false">取消</button>
        <button class="primary-button" :disabled="reviewSubmitting" @click="submitReview">{{ reviewSubmitting ? '发布中...' : '发布评价' }}</button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.order-detail-page {
  background: var(--bg-canvas);
  min-height: calc(100vh - 64px);
}

.back-orders-btn {
  display: inline-block;
  color: var(--brand-blue);
  font-size: 13px;
  font-weight: 600;
  margin-bottom: 16px;
}

.order-hero-card {
  background: white;
  border: 1px solid var(--border-line);
  border-radius: var(--radius-xl);
  padding: 28px;
  box-shadow: var(--shadow-sm);
  margin-bottom: 24px;
}

.order-hero-top {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 20px;
  margin-bottom: 24px;
}

.order-hero-top h1 {
  font-size: 22px;
  font-weight: 800;
  color: var(--text-primary);
  margin: 4px 0 6px;
}

.order-id-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: var(--text-tertiary);
}

.status-pill-lg {
  padding: 6px 14px;
  font-size: 13px;
}

.summary-tiles-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
}

.tile {
  background: var(--bg-subtle);
  border: 1px solid var(--border-line);
  border-radius: var(--radius-md);
  padding: 14px;
}

.tile-label {
  display: block;
  font-size: 11px;
  color: var(--text-tertiary);
  margin-bottom: 4px;
}

.tile strong {
  font-size: 13px;
  color: var(--text-primary);
}

.tile small {
  display: block;
  margin-top: 3px;
  color: var(--text-tertiary);
  font-size: 10px;
}

.tile.highlight .price-val {
  color: var(--price-orange);
  font-size: 18px;
  font-weight: 800;
}

.detail-card {
  background: white;
  border: 1px solid var(--border-line);
  border-radius: var(--radius-lg);
  padding: 24px;
  box-shadow: var(--shadow-sm);
  margin-bottom: 24px;
}

.card-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
}

.card-head h3 {
  font-size: 16px;
  font-weight: 700;
  color: var(--text-primary);
  margin: 0;
}

.sub-label {
  font-size: 12px;
  color: var(--text-tertiary);
}

.payment-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
}

.payment-grid > div {
  display: grid;
  gap: 4px;
  padding: 12px;
  border: 1px solid var(--border-line);
  border-radius: var(--radius-sm);
  background: var(--bg-subtle);
}

.payment-grid span,
.order-remark span {
  color: var(--text-tertiary);
  font-size: 10px;
}

.payment-grid strong {
  overflow: hidden;
  color: var(--text-primary);
  font-size: 12px;
  text-overflow: ellipsis;
}

.order-remark {
  display: grid;
  gap: 4px;
  margin: 12px 0 0;
  padding: 12px;
  border-radius: var(--radius-sm);
  background: var(--bg-subtle);
  color: var(--text-secondary);
  font-size: 12px;
  line-height: 1.5;
}

.refund-list { display: grid; gap: 10px; }
.refund-item { padding: 14px; border: 1px solid var(--border-line); border-radius: var(--radius-md); background: var(--bg-subtle); }
.refund-head { display: flex; align-items: center; justify-content: space-between; }
.refund-head > strong { color: var(--price-orange); font-size: 18px; }
.refund-item dl { display: grid; gap: 7px; margin: 12px 0 0; }
.refund-item dl div { display: grid; grid-template-columns: 80px 1fr; gap: 10px; }
.refund-item dt { color: var(--text-tertiary); font-size: 11px; }
.refund-item dd { margin: 0; color: var(--text-secondary); font-size: 11px; line-height: 1.45; }
.detail-error { display: grid; justify-items: center; gap: 9px; }
.detail-error span { color: var(--text-secondary); font-size: 12px; }

/* Timeline Stepper */
.timeline-stepper {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 10px;
}

.step {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  z-index: 2;
}

.step-icon {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  background: var(--bg-subtle);
  border: 2px solid var(--border-line);
  color: var(--text-tertiary);
  font-size: 12px;
  font-weight: 700;
  display: grid;
  place-items: center;
}

.step.completed .step-icon {
  background: var(--brand-blue);
  border-color: var(--brand-blue);
  color: white;
  box-shadow: 0 0 0 3px var(--brand-blue-tint);
}

.step-text {
  font-size: 12px;
  font-weight: 500;
  color: var(--text-tertiary);
}

.step.completed .step-text {
  color: var(--text-primary);
  font-weight: 600;
}

.step-line {
  flex: 1;
  height: 2px;
  background: var(--border-line);
  margin: 0 10px 24px;
}

.step-line.active {
  background: var(--brand-blue);
}

.bottom-actions-row {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
}

@media (max-width: 768px) {
  .summary-tiles-grid {
    grid-template-columns: repeat(2, 1fr);
  }
  .payment-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .timeline-stepper {
    flex-wrap: wrap;
    gap: 12px;
  }
  .step-line {
    display: none;
  }
}

@media (max-width: 520px) {
  .summary-tiles-grid,
  .payment-grid { grid-template-columns: 1fr; }
  .data-table { min-width: 720px; }
  .detail-card { overflow-x: auto; }
}
</style>
