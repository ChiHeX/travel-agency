<script setup>
import { onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { orderApi } from '@/api/modules'

const currentRoute = useRoute()
const detail = ref(null)
const loading = ref(true)
const refundOpen = ref(false)
const reviewOpen = ref(false)
const refund = reactive({ reason: '' })
const review = reactive({ rating: 5, content: '' })

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
  try {
    detail.value = await orderApi.detail(currentRoute.params.orderNo)
  } finally {
    loading.value = false
  }
}

async function submitRefund() {
  if (!refund.reason.trim()) return ElMessage.warning('请填写退款原因')
  await orderApi.refund(currentRoute.params.orderNo, refund)
  refundOpen.value = false
  ElMessage.success('退款申请已提交，请等待审核')
  load()
}

async function submitReview() {
  await orderApi.review(currentRoute.params.orderNo, review)
  reviewOpen.value = false
  ElMessage.success('感谢您的真实评价')
  load()
}

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
            </div>
            <div class="tile">
              <span class="tile-label">出行人数</span>
              <strong>{{ Number(detail.order.adultCount || 0) + Number(detail.order.childCount || 0) }} 位实名出行人</strong>
            </div>
            <div class="tile highlight">
              <span class="tile-label">订单实付金额</span>
              <strong class="price-val">¥{{ detail.order.totalAmount }}</strong>
            </div>
          </div>
        </div>

        <!-- Progress Timeline Card -->
        <div class="detail-card">
          <div class="card-head">
            <h3>履约时间线</h3>
            <span class="sub-label">支付状态：{{ detail.order.paymentStatus === 'PAID' ? '已支付' : '未支付' }}</span>
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
                <td>{{ traveler.gender }}</td>
                <td>{{ traveler.idType }} {{ traveler.idNoMasked }}</td>
                <td>{{ traveler.phone || '—' }}</td>
                <td>{{ traveler.emergencyName }} ({{ traveler.emergencyPhone || '—' }})</td>
              </tr>
            </tbody>
          </table>
        </div>

        <!-- Bottom Actions -->
        <div class="bottom-actions-row">
          <button
            v-if="['PAID_WAIT_CONFIRM', 'CONFIRMED'].includes(detail.order.status)"
            type="button"
            class="secondary-button danger-button"
            @click="refundOpen = true"
          >
            申请退款
          </button>
          <button
            v-if="detail.order.status === 'COMPLETED'"
            type="button"
            class="primary-button"
            @click="reviewOpen = true"
          >
            评价本次行程
          </button>
        </div>
      </template>

      <div v-else class="empty-box">
        订单记录不存在或无权访问。
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
        <button class="primary-button" @click="submitRefund">提交退款申请</button>
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
        <button class="primary-button" @click="submitReview">发布评价</button>
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
  .timeline-stepper {
    flex-wrap: wrap;
    gap: 12px;
  }
  .step-line {
    display: none;
  }
}
</style>
