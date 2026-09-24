<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { accountApi, orderApi, routeApi } from '@/api/modules'
import { createIdempotencyKey, idTypeLabels } from '@/utils/order'
import AppIcon from '@/components/AppIcon.vue'

const currentRoute = useRoute()
const router = useRouter()
const routeData = ref(null)
const savedTravelers = ref([])
const loading = ref(true)
const submitting = ref(false)
const loadError = ref('')
const submitError = ref('')
let createOrderKey = createIdempotencyKey()

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
const departure = computed(() =>
  routeData.value?.departures?.find((item) => String(item.id) === String(form.departureId))
)
const availableSeats = computed(() => Number(departure.value?.availableSeats ?? 0))
const canSubmit = computed(() =>
  departure.value?.status === 'OPEN' &&
  participantCount.value > 0 &&
  participantCount.value <= 100 &&
  availableSeats.value >= participantCount.value
)
const totalAmount = computed(() => {
  const total =
    Math.round(Number(departure.value?.adultPrice || 0) * 100) * Number(form.adultCount || 0) +
    Math.round(Number(departure.value?.childPrice || 0) * 100) * Number(form.childCount || 0)
  return (total / 100).toFixed(2)
})

function emptyTraveler() {
  return {
    sourceTravelerId: null,
    idNoMasked: '',
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
  if (!Number.isInteger(participantCount.value) || participantCount.value < 0 || participantCount.value > 100) return
  while (form.travelers.length < participantCount.value) form.travelers.push(emptyTraveler())
  if (form.travelers.length > participantCount.value) form.travelers.splice(participantCount.value)
}

watch(participantCount, syncTravelers, { immediate: true })

onMounted(async () => {
  loadError.value = ''
  try {
    const [detailResult, travelersResult] = await Promise.allSettled([
      routeApi.detail(currentRoute.query.routeId),
      accountApi.travelers()
    ])
    if (detailResult.status === 'rejected') throw detailResult.reason
    routeData.value = detailResult.value
    savedTravelers.value = travelersResult.status === 'fulfilled' ? travelersResult.value || [] : []
  } catch (error) {
    loadError.value = error.message || '报名资料加载失败，请返回线路详情重试'
  } finally {
    loading.value = false
  }
})

function selectSavedTraveler(target, travelerId) {
  const selected = savedTravelers.value.find((item) => String(item.id) === String(travelerId))
  if (!selected) {
    Object.assign(target, emptyTraveler())
    return
  }
  Object.assign(target, {
    sourceTravelerId: selected.id,
    idNoMasked: selected.idNoMasked,
    name: selected.name,
    gender: selected.gender,
    birthDate: selected.birthDate,
    idType: selected.idType,
    idNo: '',
    phone: selected.phone || '',
    emergencyName: selected.emergencyName,
    emergencyPhone: selected.emergencyPhone
  })
}

function savedTravelerDisabled(savedId, currentIndex) {
  return form.travelers.some(
    (item, index) => index !== currentIndex && String(item.sourceTravelerId) === String(savedId)
  )
}

function returnToRoute() {
  const routeId = String(currentRoute.query.routeId || '')
  const previousPath = window.history.state?.back
  if (previousPath) {
    const previousRoute = router.resolve(previousPath)
    if (previousRoute.name === 'route-detail' && String(previousRoute.params.id) === routeId) {
      router.back()
      return
    }
  }
  router.replace(routeId ? { name: 'route-detail', params: { id: routeId } } : { name: 'routes' })
}

async function submit() {
  if (submitting.value) return
  submitError.value = ''
  if (![form.adultCount, form.childCount].every((value) => Number.isInteger(value) && value >= 0) || participantCount.value > 100) return ElMessage.warning('出行人数须为非负整数，总人数不超过 100 人')
  if (!departure.value) return ElMessage.warning('团期信息加载失败，请返回线路详情重新选择')
  if (departure.value.status !== 'OPEN' || availableSeats.value < participantCount.value) {
    return ElMessage.warning('当前团期状态或剩余名额已不满足报名人数，请返回重新选择')
  }
  if (!form.contactName.trim() || !/^1[3-9]\d{9}$/.test(form.contactPhone)) {
    return ElMessage.warning('请填写联系人姓名和有效的 11 位手机号')
  }
  if (form.contactEmail && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(form.contactEmail)) {
    return ElMessage.warning('请填写有效的联系人邮箱')
  }
  if (
    participantCount.value <= 0 ||
    participantCount.value > 100 ||
    form.travelers.some(
      (item) =>
        !item.name.trim() ||
        !item.birthDate ||
        item.idNo.trim().length < 3 ||
        !item.emergencyName.trim() ||
        item.emergencyPhone.trim().length < 3
    )
  ) {
    return ElMessage.warning('请完整填写每位出行人的实名姓名、证件号码与紧急联系人')
  }
  submitting.value = true
  try {
    const order = await orderApi.create({
      departureId: String(form.departureId),
      contactName: form.contactName.trim(),
      contactPhone: form.contactPhone.trim(),
      contactEmail: form.contactEmail || null,
      adultCount: Number(form.adultCount),
      childCount: Number(form.childCount),
      remark: form.remark || null,
      travelers: form.travelers.map((traveler, index) => ({
        travelerType: index < Number(form.adultCount) ? 'ADULT' : 'CHILD',
        sourceTravelerId: traveler.sourceTravelerId || null,
        name: traveler.name.trim(),
        gender: traveler.gender,
        birthDate: traveler.birthDate,
        idType: traveler.idType,
        idNo: traveler.idNo.trim(),
        phone: traveler.phone || null,
        emergencyName: traveler.emergencyName.trim(),
        emergencyPhone: traveler.emergencyPhone.trim()
      }))
    }, createOrderKey)
    createOrderKey = createIdempotencyKey()
    ElMessage.success('订单已创建，请尽快完成支付')
    router.replace({ name: 'order-payment', params: { orderNo: order.orderNo } })
  } catch (cause) { submitError.value = cause.message || '订单提交失败，请重试'
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="checkout-page">
    <main class="booking-shell">
      <button
        type="button"
        class="back-link"
        @click="returnToRoute"
      ><AppIcon name="chevron-left" size="14" /><span>{{ currentRoute.query.routeId ? '返回线路详情' : '返回线路列表' }}</span></button>
      <header class="booking-heading">
        <h1>填写报名信息</h1>
        <p>核对团期，填写联系人与出行人信息。</p>
      </header>

      <div v-if="loading" class="admin-panel">
        <el-skeleton :rows="8" animated />
      </div>

      <div v-else-if="loadError" class="empty-box booking-error">
        <strong>报名信息暂时无法加载</strong>
        <p>{{ loadError }}</p>
        <button type="button" class="secondary-button" @click="returnToRoute">{{ currentRoute.query.routeId ? '返回线路详情' : '返回线路列表' }}</button>
      </div>

      <template v-else-if="departure && routeData?.route">
        <div class="booking-grid">
          <div class="booking-form">
            <section class="booking-section" aria-labelledby="contact-heading">
              <header class="booking-card-header">
                <h2 id="contact-heading">人数与联系人</h2>
                <p>请填写能接收出团通知的联系人。</p>
              </header>

              <div class="passenger-counters-grid">
                <div class="counter-box">
                  <label for="adult-count">成人</label>
                  <div class="counter-control">
                    <input id="adult-count" v-model.number="form.adultCount" type="number" min="1" :max="availableSeats" class="num-input" />
                    <span class="unit-text">人</span>
                  </div>
                </div>

                <div class="counter-box">
                  <label for="child-count">儿童</label>
                  <div class="counter-control">
                    <input id="child-count" v-model.number="form.childCount" type="number" min="0" :max="availableSeats" class="num-input" />
                    <span class="unit-text">人</span>
                  </div>
                </div>
              </div>

              <div class="contact-inputs-grid">
                <div class="form-field">
                  <label>联系人姓名 <span class="req">*</span></label>
                  <input v-model="form.contactName" placeholder="用于接收出团提醒" required />
                </div>

                <div class="form-field">
                  <label>手机号码 <span class="req">*</span></label>
                  <input v-model="form.contactPhone" inputmode="tel" placeholder="11位手机号码" required />
                </div>

                <div class="form-field wide">
                  <label>电子邮箱（选填）</label>
                  <input v-model="form.contactEmail" type="email" placeholder="用于接收电子行程单与出团通知" />
                </div>
              </div>
            </section>

            <section class="booking-section" aria-labelledby="travelers-heading">
              <header class="booking-card-header">
                <h2 id="travelers-heading">出行人</h2>
                <p>共 {{ participantCount }} 位，请填写与证件一致的实名信息。</p>
              </header>

              <div class="travelers-list">
                <div
                  v-for="(traveler, index) in form.travelers"
                  :key="index"
                  class="traveler-entry"
                >
                  <div class="traveler-card-head">
                    <h3>出行人 {{ index + 1 }}</h3>
                    <span>{{ index < form.adultCount ? '成人' : '儿童' }}</span>
                  </div>

                  <div v-if="savedTravelers.length" class="saved-traveler-picker">
                    <label :for="`saved-traveler-${index}`">使用常用出行人</label>
                    <select
                      :id="`saved-traveler-${index}`"
                      :value="traveler.sourceTravelerId || ''"
                      @change="selectSavedTraveler(traveler, $event.target.value)"
                    >
                      <option value="">不使用常用出行人</option>
                      <option
                        v-for="saved in savedTravelers"
                        :key="saved.id"
                        :value="saved.id"
                        :disabled="savedTravelerDisabled(saved.id, index)"
                      >
                        {{ saved.name }} · {{ idTypeLabels[saved.idType] || saved.idType }} {{ saved.idNoMasked }}
                      </option>
                    </select>
                    <p v-if="traveler.idNoMasked">证件号仅保存脱敏信息，本次报名需重新填写完整号码。</p>
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
                      <input
                        v-model="traveler.idNo"
                        autocomplete="off"
                        :placeholder="traveler.idNoMasked ? `原资料：${traveler.idNoMasked}，请重新输入完整号码` : '请输入完整有效证件号码'"
                        required
                      />
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
                      <label>紧急联系人电话 <span class="req">*</span></label>
                      <input v-model="traveler.emergencyPhone" inputmode="tel" placeholder="紧急备用联络号码" />
                    </div>
                  </div>
                </div>
              </div>
            </section>

            <section class="booking-section booking-remarks" aria-labelledby="remarks-heading">
              <header class="booking-card-header">
                <h2 id="remarks-heading">其他需求 <span>选填</span></h2>
              </header>
              <div class="form-field">
                <label for="booking-remark">需要提前告诉旅行社的事</label>
                <textarea
                  id="booking-remark"
                  v-model="form.remark"
                  rows="3"
                  placeholder="例如饮食或行动辅助需求"
                ></textarea>
              </div>
            </section>
          </div>

          <aside class="booking-summary" aria-label="团期与费用摘要">
            <div class="summary-inner">
              <header class="booking-card-header">
                <h2>行程确认</h2>
              </header>
              <h3>{{ routeData.route.name }}</h3>
              <p class="summary-dates">{{ departure.startDate }} 至 {{ departure.endDate }}</p>
              <dl class="summary-prices">
                <div><dt>成人 · ¥{{ departure.adultPrice }}/人</dt><dd>{{ form.adultCount }} 人</dd></div>
                <div><dt>儿童 · ¥{{ departure.childPrice }}/人</dt><dd>{{ form.childCount }} 人</dd></div>
              </dl>
              <p class="summary-seats">当前剩余 {{ departure.availableSeats }} 个名额</p>
              <div class="summary-total">
                <span>预估金额</span>
                <strong>¥{{ totalAmount }}</strong>
              </div>
              <p class="summary-note">实际金额以订单创建结果为准。</p>
              <p v-if="submitError" class="form-error" role="alert">{{ submitError }}</p>
              <button
                type="button"
                class="primary-button checkout-submit-btn"
                :disabled="submitting || !canSubmit"
                @click="submit"
              >
                {{ submitting ? '正在创建订单...' : canSubmit ? '提交订单' : '当前团期不可报名' }}
              </button>
            </div>
          </aside>
        </div>
      </template>

      <div v-else class="empty-box booking-error">
        当前团期不存在或已不可报名，请返回线路详情重新选择。
      </div>
    </main>
  </div>
</template>

<style scoped>
.checkout-page {
  min-height: 100%;
  background: #f8f9fa;
  color: #242424;
  font-family: "Segoe UI", "Microsoft YaHei", "PingFang SC", sans-serif;
}

.booking-shell {
  width: min(1120px, calc(100% - 64px));
  margin-left: 32px;
  padding: 32px 0 72px;
}

.back-link {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  min-height: 32px;
  margin-bottom: 24px;
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--theme-blue);
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
}

.back-link:hover { color: var(--theme-blue-active); }
.back-link:focus-visible { outline: 2px solid var(--theme-blue); outline-offset: 3px; }

.booking-heading { margin-bottom: 32px; }
.booking-heading h1 {
  color: #242424;
  font-size: 22px;
  font-weight: 600;
  line-height: 1.3;
}
.booking-heading p {
  margin-top: 6px;
  color: #616161;
  font-size: 14px;
}

.booking-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 300px;
  align-items: start;
  gap: 20px;
}
.booking-form { display: grid; gap: 12px; min-width: 0; }
.booking-section {
  padding: 24px;
  border: 1px solid #e1e1e1;
  border-radius: 8px;
  background: #fff;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.06);
}
.booking-card-header {
  margin: -24px -24px 24px;
  padding: 18px 24px;
  border-bottom: 1px solid #ececec;
}
.booking-card-header h2 {
  color: #242424;
  font-size: 18px;
  font-weight: 600;
  line-height: 1.4;
}
.booking-card-header h2 span {
  color: #616161;
  font-size: 13px;
  font-weight: 400;
}
.booking-card-header p {
  margin-top: 3px;
  color: #616161;
  font-size: 14px;
  line-height: 1.4;
}

.passenger-counters-grid,
.contact-inputs-grid,
.traveler-fields-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 20px;
}
.passenger-counters-grid { gap: 20px; margin-bottom: 20px; }
.contact-inputs-grid .wide,
.traveler-fields-grid .wide { grid-column: 1 / -1; }
.counter-box {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 58px;
  padding: 8px 14px;
  border: 1px solid var(--border-divider);
  border-radius: 10px;
}
.counter-box label { font-size: 14px; font-weight: 400; }
.counter-control { display: flex; align-items: center; gap: 7px; }
.num-input {
  width: 62px;
  height: 38px;
  padding: 0 3px;
  border: 1px solid var(--border-divider);
  border-radius: 8px;
  background: #fff;
  text-align: center;
  font-size: 14px;
}
.num-input:focus-visible { outline: 2px solid var(--theme-blue); outline-offset: 1px; }
.unit-text { color: #616161; font-size: 14px; }
.req { color: #c52019; }

.checkout-page .form-field label {
  color: #424242;
  font-size: 14px;
  font-weight: 400;
}
.checkout-page .form-field { gap: 7px; margin-bottom: 16px; }
.checkout-page .form-field input,
.checkout-page .form-field select,
.checkout-page .form-field textarea {
  min-height: 44px;
  border-color: #d2d2d7;
  border-radius: 9px;
  font-size: 14px;
}
.checkout-page .form-field input:focus-visible,
.checkout-page .form-field select:focus-visible,
.checkout-page .form-field textarea:focus-visible {
  outline: 2px solid var(--theme-blue);
  outline-offset: 1px;
}
.checkout-page .form-field textarea { min-height: 84px; }

.traveler-entry { padding: 20px 0; border-top: 1px solid #e1e1e1; }
.traveler-entry:first-child { padding-top: 0; border-top: 0; }
.traveler-card-head {
  display: flex;
  align-items: baseline;
  gap: 10px;
  margin-bottom: 16px;
}
.traveler-card-head h3 { font-size: 16px; font-weight: 600; }
.traveler-card-head span { color: #616161; font-size: 13px; }
.saved-traveler-picker { margin-bottom: 16px; }
.saved-traveler-picker label {
  display: block;
  margin-bottom: 7px;
  color: #424242;
  font-size: 14px;
  font-weight: 400;
}
.saved-traveler-picker select {
  width: 100%;
  min-height: 44px;
  padding: 0 12px;
  border: 1px solid #d2d2d7;
  border-radius: 9px;
  background: #fff;
  color: #242424;
  font-size: 14px;
}
.saved-traveler-picker select:focus-visible { outline: 2px solid var(--theme-blue); outline-offset: 1px; }
.saved-traveler-picker p { margin-top: 8px; color: #616161; font-size: 13px; }
.booking-remarks .form-field { margin-bottom: 0; }

.booking-summary {
  position: sticky;
  top: 24px;
  padding: 24px;
  border: 1px solid #e1e1e1;
  border-radius: 8px;
  background: #fff;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.06);
}
.summary-inner h3 { font-size: 16px; font-weight: 600; line-height: 1.4; }
.summary-dates { margin-top: 8px; color: #616161; font-size: 14px; }
.summary-prices { margin-top: 24px; }
.summary-prices div {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 10px;
  color: #616161;
  font-size: 14px;
}
.summary-prices dd { flex: 0 0 auto; color: #242424; }
.summary-seats { margin-top: 16px; color: #616161; font-size: 13px; }
.summary-total {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
  margin-top: 20px;
  padding-top: 18px;
  border-top: 1px solid #e1e1e1;
  font-size: 14px;
  font-weight: 600;
}
.summary-total strong { font-size: 22px; font-weight: 600; }
.summary-note { margin-top: 6px; color: #616161; font-size: 12px; }
.checkout-submit-btn {
  width: 100%;
  min-height: 44px;
  margin-top: 20px;
  border-radius: 10px;
  font-size: 14px;
  font-weight: 600;
}
.booking-summary .form-error { margin-top: 16px; }
.booking-error { display: grid; justify-items: center; gap: 10px; }
.booking-error p { color: #616161; }

@media (max-width: 900px) {
  .booking-grid { grid-template-columns: minmax(0, 1fr); gap: 12px; }
  .booking-summary { position: static; }
}

@media (max-width: 600px) {
  .booking-shell { width: calc(100% - 40px); margin-left: 20px; padding: 24px 0 72px; }
  .booking-heading { margin-bottom: 24px; }
  .booking-heading h1 { font-size: 20px; }
  .booking-section { padding: 20px; }
  .booking-card-header { margin: -20px -20px 20px; padding: 16px 20px; }
  .passenger-counters-grid,
  .contact-inputs-grid,
  .traveler-fields-grid { grid-template-columns: minmax(0, 1fr); }
  .contact-inputs-grid .wide,
  .traveler-fields-grid .wide { grid-column: auto; }
}
</style>
