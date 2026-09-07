<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { orderApi, routeApi } from '@/api/modules'

const currentRoute = useRoute()
const router = useRouter()
const routeData = ref(null)
const loading = ref(true)
const submitting = ref(false)

const form = reactive({
  departureId: String(currentRoute.query.departureId || ''),
  adultCount: 1,
  childCount: 0,
  contactName: '',
  contactPhone: '',
  contactEmail: '',
  remark: '',
  travelers: []
})

const participantCount = computed(() => Number(form.adultCount || 0) + Number(form.childCount || 0))
const departure = computed(() => routeData.value?.departures?.find((item) => item.id === form.departureId))
const totalAmount = computed(
  () =>
    Number(departure.value?.adultPrice || 0) * Number(form.adultCount || 0) +
    Number(departure.value?.childPrice || 0) * Number(form.childCount || 0)
)

function emptyTraveler() {
  return {
    name: '',
    gender: 'MALE',
    birthDate: '',
    idType: 'CHINESE_ID_CARD',
    idNo: '',
    phone: '',
    emergencyName: '',
    emergencyPhone: ''
  }
}

function syncTravelers() {
  while (form.travelers.length < participantCount.value) form.travelers.push(emptyTraveler())
  if (form.travelers.length > participantCount.value) form.travelers.splice(participantCount.value)
}

watch(participantCount, syncTravelers, { immediate: true })

onMounted(async () => {
  try {
    routeData.value = await routeApi.detail(currentRoute.query.routeId || 0)
  } catch (_) {
    /* quiet */
  } finally {
    loading.value = false
  }
})

async function submit() {
  if (!departure.value) return ElMessage.warning('团期信息加载失败，请返回线路详情重新选择')
  if (
    participantCount.value <= 0 ||
    form.travelers.some(
      (item) => !item.name || !item.birthDate || !item.idNo || !item.emergencyName || !item.emergencyPhone
    )
  ) {
    return ElMessage.warning('请完整填写每位出行人的实名姓名、证件号码与紧急联系人')
  }
  submitting.value = true
  try {
    const order = await orderApi.create({
      ...form,
      contactEmail: form.contactEmail || null,
      adultCount: Number(form.adultCount),
      childCount: Number(form.childCount),
      travelers: form.travelers.map((traveler, index) => ({
        ...traveler,
        phone: traveler.phone || null,
        travelerType: index < Number(form.adultCount) ? 'ADULT' : 'CHILD'
      }))
    })
    ElMessage.success('订单已创建，请尽快完成支付')
    router.replace({ name: 'order-detail', params: { orderNo: order.orderNo } })
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="checkout-page">
    <div class="container narrow-container page-section">
      <!-- Breadcrumb / Header -->
      <div class="section-head">
        <div>
          <span class="eyebrow">CHECKOUT &amp; BOOKING</span>
          <h2>填写跟团报名资料</h2>
          <p>完成出行人实名信息登记，订单生成后将保存不可篡改的快照记录。</p>
        </div>
      </div>

      <div v-if="loading" class="admin-panel">
        <el-skeleton :rows="8" animated />
      </div>

      <template v-else>
        <!-- Departure Overview Banner (旅游平台 Style) -->
        <div class="departure-summary-card" v-if="departure && routeData?.route">
          <div class="dep-icon">
            <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <polygon points="3 11 22 2 13 21 11 13 3 11" />
            </svg>
          </div>
          <div class="dep-details">
            <span class="eyebrow">CONFIRMED TOUR SCHEDULE</span>
            <h3>{{ routeData.route.name }}</h3>
            <div class="dep-meta-row">
              <span>团期日期：<strong>{{ departure.startDate }} 至 {{ departure.endDate }}</strong></span>
              <span>·</span>
              <span>成人 ¥{{ departure.adultPrice }}/人</span>
              <span>·</span>
              <span>儿童 ¥{{ departure.childPrice }}/人</span>
            </div>
          </div>
        </div>

        <!-- Section 1: Passenger Counter & Contact Info -->
        <div class="form-card">
          <div class="card-section-title">
            <span class="step-badge">1</span>
            <h3>出行人数与联系方式</h3>
          </div>

          <div class="passenger-counters-grid">
            <div class="counter-box">
              <div class="counter-label">
                <strong>成人出行人</strong>
                <span>包含全程门票及标准床位</span>
              </div>
              <div class="counter-control">
                <input v-model.number="form.adultCount" type="number" min="1" max="50" class="num-input" />
                <span class="unit-text">人</span>
              </div>
            </div>

            <div class="counter-box">
              <div class="counter-label">
                <strong>儿童出行人</strong>
                <span>12周岁以下，含车位与导服</span>
              </div>
              <div class="counter-control">
                <input v-model.number="form.childCount" type="number" min="0" max="50" class="num-input" />
                <span class="unit-text">人</span>
              </div>
            </div>
          </div>

          <div class="contact-inputs-grid">
            <div class="form-field">
              <label>主联系人姓名 <span class="req">*</span></label>
              <input v-model="form.contactName" placeholder="用于接收出团提醒" required />
            </div>

            <div class="form-field">
              <label>主联系人手机号 <span class="req">*</span></label>
              <input v-model="form.contactPhone" inputmode="tel" placeholder="11位手机号码" required />
            </div>

            <div class="form-field wide">
              <label>联系人电子邮箱（可选）</label>
              <input v-model="form.contactEmail" type="email" placeholder="用于接收电子行程单与出团通知" />
            </div>
          </div>
        </div>

        <!-- Section 2: Traveler Real-Name Details (系统 Card Style) -->
        <div class="form-card">
          <div class="card-section-title">
            <span class="step-badge">2</span>
            <div>
              <h3>出行人实名信息</h3>
              <span class="title-sub">已选择共 {{ participantCount }} 位出行人，请如实填写证件信息以购买保险</span>
            </div>
          </div>

          <div class="travelers-list">
            <div
              v-for="(traveler, index) in form.travelers"
              :key="index"
              class="traveler-sub-card"
            >
              <div class="traveler-card-head">
                <span class="traveler-index-badge">出行人 {{ index + 1 }}</span>
                <span class="traveler-type-tag">{{ index < form.adultCount ? '成人票' : '儿童票' }}</span>
              </div>

              <div class="traveler-fields-grid">
                <div class="form-field">
                  <label>真实姓名 <span class="req">*</span></label>
                  <input v-model="traveler.name" placeholder="请与证件一致" required />
                </div>

                <div class="form-field">
                  <label>性别</label>
                  <select v-model="traveler.gender">
                    <option value="MALE">男</option>
                    <option value="FEMALE">女</option>
                    <option value="OTHER">其他</option>
                  </select>
                </div>

                <div class="form-field">
                  <label>出生日期</label>
                  <input v-model="traveler.birthDate" type="date" />
                </div>

                <div class="form-field">
                  <label>证件类型</label>
                  <select v-model="traveler.idType">
                    <option value="CHINESE_ID_CARD">身份证</option>
                    <option value="PASSPORT">护照</option>
                    <option value="OTHER">其他证件</option>
                  </select>
                </div>

                <div class="form-field wide">
                  <label>证件号码 <span class="req">*</span></label>
                  <input v-model="traveler.idNo" placeholder="请输入完整有效证件号码" required />
                </div>

                <div class="form-field">
                  <label>出行人电话</label>
                  <input v-model="traveler.phone" inputmode="tel" placeholder="可选填写" />
                </div>

                <div class="form-field">
                  <label>紧急联系人姓名 <span class="req">*</span></label>
                  <input v-model="traveler.emergencyName" placeholder="如：父母/配偶" required />
                </div>

                <div class="form-field wide">
                  <label>紧急联系人电话</label>
                  <input v-model="traveler.emergencyPhone" inputmode="tel" placeholder="紧急备用联络号码" />
                </div>
              </div>
            </div>
          </div>
        </div>

        <!-- Section 3: Remarks -->
        <div class="form-card">
          <div class="card-section-title">
            <span class="step-badge">3</span>
            <h3>行程特殊要求备注（可选）</h3>
          </div>

          <div class="form-field">
            <textarea
              v-model="form.remark"
              rows="3"
              placeholder="如：清真饮食、同住需求、轮椅辅助或集合地点说明等，旅行社将尽力配合安排。"
            ></textarea>
          </div>
        </div>

        <!-- Sticky Checkout Action Bar -->
        <div class="sticky-checkout-bar">
          <div class="price-breakdown">
            <span class="price-label">订单应付总额</span>
            <div class="price-figure">
              <span class="curr">¥</span>
              <strong>{{ totalAmount.toFixed(2) }}</strong>
            </div>
            <span class="price-detail-hint">包含 {{ form.adultCount }} 成人 / {{ form.childCount }} 儿童</span>
          </div>

          <button
            type="button"
            class="primary-button checkout-submit-btn"
            :disabled="submitting"
            @click="submit"
          >
            {{ submitting ? '正在创建订单...' : '立即提交订单' }}
          </button>
        </div>
      </template>
    </div>
  </div>
</template>

<style scoped>
.checkout-page {
  background: var(--bg-canvas);
  min-height: calc(100vh - 64px);
}

.departure-summary-card {
  background: white;
  border: 1px solid var(--border-line);
  border-radius: var(--radius-lg);
  padding: 20px 24px;
  display: flex;
  align-items: center;
  gap: 18px;
  margin-bottom: 24px;
  box-shadow: var(--shadow-xs);
}

.dep-icon {
  width: 44px;
  height: 44px;
  border-radius: var(--radius-md);
  background: var(--brand-blue-subtle);
  color: var(--brand-blue);
  display: grid;
  place-items: center;
  flex-shrink: 0;
}

.dep-details h3 {
  font-size: 16px;
  font-weight: 700;
  color: var(--text-primary);
  margin: 2px 0 4px;
}

.dep-meta-row {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: var(--text-secondary);
}

.dep-meta-row strong {
  color: var(--brand-blue-dark);
}

.form-card {
  background: white;
  border: 1px solid var(--border-line);
  border-radius: var(--radius-lg);
  padding: 28px;
  margin-bottom: 24px;
  box-shadow: var(--shadow-xs);
}

.card-section-title {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 20px;
  padding-bottom: 14px;
  border-bottom: 1px solid var(--border-line);
}

.step-badge {
  width: 26px;
  height: 26px;
  border-radius: 50%;
  background: var(--brand-blue);
  color: white;
  font-size: 13px;
  font-weight: 700;
  display: grid;
  place-items: center;
}

.card-section-title h3 {
  font-size: 16px;
  font-weight: 700;
  margin: 0;
  color: var(--text-primary);
}

.title-sub {
  font-size: 12px;
  color: var(--text-tertiary);
  display: block;
  margin-top: 2px;
}

.passenger-counters-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
  margin-bottom: 20px;
}

.counter-box {
  background: var(--bg-subtle);
  border: 1px solid var(--border-line);
  border-radius: var(--radius-md);
  padding: 14px 18px;
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.counter-label strong {
  display: block;
  font-size: 13px;
  color: var(--text-primary);
}

.counter-label span {
  font-size: 11px;
  color: var(--text-tertiary);
}

.counter-control {
  display: flex;
  align-items: center;
  gap: 6px;
}

.num-input {
  width: 60px;
  height: 36px;
  text-align: center;
  font-size: 15px;
  font-weight: 700;
  border: 1px solid var(--border-line);
  border-radius: var(--radius-sm);
  background: white;
}

.contact-inputs-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
}

.contact-inputs-grid .wide {
  grid-column: 1 / -1;
}

.req {
  color: var(--danger-red);
}

/* Travelers Sub-Cards */
.travelers-list {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.traveler-sub-card {
  background: var(--bg-subtle);
  border: 1px solid var(--border-line);
  border-radius: var(--radius-md);
  padding: 18px;
}

.traveler-card-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 14px;
}

.traveler-index-badge {
  font-size: 13px;
  font-weight: 700;
  color: var(--brand-blue-dark);
}

.traveler-type-tag {
  font-size: 11px;
  font-weight: 600;
  background: white;
  padding: 2px 8px;
  border-radius: var(--radius-full);
  border: 1px solid var(--border-line);
  color: var(--text-secondary);
}

.traveler-fields-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}

.traveler-fields-grid .wide {
  grid-column: 1 / -1;
}

/* Sticky Checkout Bar */
.sticky-checkout-bar {
  position: sticky;
  bottom: 20px;
  background: rgba(255, 255, 255, 0.94);
  backdrop-filter: blur(20px);
  border: 1px solid var(--border-line);
  border-radius: var(--radius-xl);
  padding: 16px 28px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  box-shadow: var(--shadow-xl);
  z-index: 30;
}

.price-breakdown {
  display: flex;
  flex-direction: column;
}

.price-label {
  font-size: 11px;
  color: var(--text-tertiary);
  font-weight: 600;
  text-transform: uppercase;
}

.price-figure {
  color: var(--price-orange);
  display: flex;
  align-items: baseline;
}

.price-figure .curr {
  font-size: 14px;
  font-weight: 700;
}

.price-figure strong {
  font-size: 26px;
  font-weight: 800;
  letter-spacing: -0.02em;
  margin-left: 2px;
}

.price-detail-hint {
  font-size: 11px;
  color: var(--text-tertiary);
}

.checkout-submit-btn {
  min-height: 44px;
  padding: 0 32px;
  font-size: 15px;
  font-weight: 700;
  border-radius: var(--radius-full);
}

@media (max-width: 640px) {
  .passenger-counters-grid {
    grid-template-columns: 1fr;
  }
  .contact-inputs-grid {
    grid-template-columns: 1fr;
  }
  .traveler-fields-grid {
    grid-template-columns: 1fr;
  }
  .sticky-checkout-bar {
    flex-direction: column;
    align-items: stretch;
    gap: 12px;
  }
}
</style>
