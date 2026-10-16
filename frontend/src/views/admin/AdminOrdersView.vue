<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { adminApi } from '@/api/modules'
import RequestState from '@/components/RequestState.vue'
import {
  genderLabels,
  idTypeLabels,
  orderStatusLabels,
  paymentStatusLabels,
  refundStatusLabels
} from '@/utils/order'
import { codePointLength } from '@/utils/text'

const rows = ref([])
const loading = ref(false)
const error = ref('')
/** 正在确认报名的订单号。 */
const confirmingOrderNo = ref(null)
/** 正在加载详情的订单号。 */
const detailLoadingOrderNo = ref(null)

const query = reactive({ page: 1, size: 20, keyword: '', status: '' })
const total = ref(0)
const totalPages = ref(0)

const KEYWORD_MAX = 100

/**
 * 当前业务使用的八个订单状态（不包含契约保留值）。
 *
 * <p><b>关于 REFUND_PROCESSING / REFUND_REJECTED</b>：这两个值是
 * {@code OrderStatus} 枚举里的<b>保留值</b>（契约里声明了，但当前业务不使用）；
 * 它们<b>不是 {@code RefundStatus} 的取值</b>。</p>
 *
 * <p>退款单自身的状态由 {@code RefundStatus} 表达，是<b>不带前缀</b>的
 * {@code APPLYING / PROCESSING / REFUNDED / REJECTED}。因此：</p>
 * <ul>
 *   <li>订单筛选下拉里<b>不出现</b> REFUND_PROCESSING / REFUND_REJECTED；</li>
 *   <li>退款处理中 / 拒绝的信息，在订单详情的「退款记录」区块按 {@code RefundStatus}
 *       展示（取的是 {@code refund.status}，不是 {@code order.status}）。</li>
 * </ul>
 */
const ORDER_STATUS_OPTIONS = [
  { value: 'WAIT_PAY', label: '待支付' },
  { value: 'PAID_WAIT_CONFIRM', label: '待确认' },
  { value: 'CONFIRMED', label: '已确认' },
  { value: 'TRAVELLING', label: '行程中' },
  { value: 'COMPLETED', label: '已完成' },
  { value: 'CANCELLED', label: '已取消' },
  { value: 'REFUND_APPLYING', label: '退款审核中' },
  { value: 'REFUNDED', label: '已退款' }
]

/** 当前业务使用的八种订单状态的标签颜色，不展示契约保留值。 */
const ORDER_STATUS_CLASS = {
  WAIT_PAY: 'warning',
  PAID_WAIT_CONFIRM: 'warning',
  CONFIRMED: 'success',
  TRAVELLING: 'success',
  COMPLETED: 'success',
  CANCELLED: 'danger',
  REFUND_APPLYING: 'warning',
  REFUNDED: 'danger'
}

const orderStatusLabel = (status) => orderStatusLabels[status] || status
const orderStatusClass = (status) => ORDER_STATUS_CLASS[status] || ''

// ---------- 详情弹窗 ----------
const detailVisible = ref(false)
const detail = ref(null)
const detailError = ref('')
/**
 * 详情请求序号：只允许「当前打开的那次」写回弹窗。
 *
 * <p>场景：打开订单 A、关闭后打开订单 B，A 的响应晚到后若无条件写回 detail，
 * 弹窗会跳回 A 的内容；A 里若有"确认报名"按钮，还会让工作人员对着 B 的页面
 * 误点 A 的操作。加序号 + 当前订单号校验后，过期响应一律丢弃。</p>
 */
let detailRequestSeq = 0
/** 当前弹窗正在查看的订单号；关闭弹窗时置空。 */
const activeDetailOrderNo = ref(null)

/**
 * 列表请求序号：只允许「最新一次」写回 rows / total / error / loading。
 * 快速切换筛选或翻页时，旧响应晚到会覆盖新结果；靠序号把过期响应丢弃。
 */
let listRequestSeq = 0

async function load(retryOnEmptyPage = true) {
  const seq = ++listRequestSeq
  loading.value = true
  error.value = ''
  try {
    const result = await adminApi.orders({
      page: query.page,
      size: query.size,
      keyword: query.keyword.trim() || undefined,
      status: query.status || undefined
    })
    // 期间用户又改了筛选或翻页：这次响应已过期，丢弃
    if (seq !== listRequestSeq) return
    rows.value = result?.items || []
    total.value = Number(result?.total ?? rows.value.length)
    totalPages.value = Number(result?.totalPages ?? (rows.value.length ? 1 : 0))
    // 筛选后当前页可能空了：回退一页
    if (retryOnEmptyPage && !rows.value.length && query.page > 1) {
      query.page -= 1
      await load(false)
    }
  } catch (cause) {
    if (seq !== listRequestSeq) return
    rows.value = []
    error.value = cause.message || '订单列表加载失败'
  } finally {
    if (seq === listRequestSeq) loading.value = false
  }
}

function search() {
  const keyword = query.keyword.trim()
  if (codePointLength(keyword) > KEYWORD_MAX) {
    ElMessage.warning(`搜索关键字最多 ${KEYWORD_MAX} 个字符`)
    return
  }
  query.page = 1
  load()
}

function resetSearch() {
  query.keyword = ''
  query.status = ''
  query.page = 1
  load()
}

function goToPage(delta) {
  const next = query.page + delta
  if (next < 1 || (totalPages.value && next > totalPages.value)) return
  query.page = next
  load()
}

// ---------- 详情 ----------
async function openDetail(row) {
  const seq = ++detailRequestSeq
  const orderNo = row.orderNo
  detailVisible.value = true
  activeDetailOrderNo.value = orderNo
  detailLoadingOrderNo.value = orderNo
  detailError.value = ''
  detail.value = null
  try {
    const data = await adminApi.order(orderNo)
    // 期间用户又打开了别的订单，或关闭了弹窗：这次响应已过期，丢弃
    if (seq !== detailRequestSeq) return
    detail.value = data
  } catch (cause) {
    if (seq !== detailRequestSeq) return
    detailError.value = cause.message || '订单详情加载失败'
  } finally {
    if (seq === detailRequestSeq) detailLoadingOrderNo.value = null
  }
}

/** 关闭弹窗时让所有在途请求失效，避免它们晚到后把弹窗内容写脏。 */
function onDetailClose() {
  detailRequestSeq += 1
  activeDetailOrderNo.value = null
  detail.value = null
  detailLoadingOrderNo.value = null
  detailError.value = ''
}

// ---------- 确认报名 ----------
async function confirm(row) {
  if (confirmingOrderNo.value) return
  try {
    await ElMessageBox.confirm(
      `确认审核通过订单「${row.orderNo}」吗？系统会再次校验团期名额与出行人实名资料。`,
      '确认报名',
      { type: 'warning', confirmButtonText: '确认通过', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  confirmingOrderNo.value = row.orderNo
  try {
    await adminApi.confirmOrder(row.orderNo)
    ElMessage.success('订单已确认通过')
    await load()
    // 只有当前弹窗还停在这条订单上时才刷新详情
    if (detailVisible.value && activeDetailOrderNo.value === row.orderNo) {
      const seq = detailRequestSeq
      const fresh = await adminApi.order(row.orderNo)
      if (seq === detailRequestSeq && activeDetailOrderNo.value === row.orderNo) {
        detail.value = fresh
      }
    }
  } catch (cause) {
    if (cause.status === 409) ElMessage.warning(cause.message || '当前订单状态不允许确认')
    else ElMessage.error(cause.message || '确认失败')
  } finally {
    confirmingOrderNo.value = null
  }
}

onMounted(load)
</script>

<template>
  <div class="admin-orders-page">
    <div class="admin-page-head">
      <div>
        <h2>报名订单管理</h2>
        <p>支付成功后需工作人员人工二次核实；支持按订单号、联系人或手机号搜索。</p>
      </div>
    </div>

    <div class="admin-panel">
      <form class="toolbar" @submit.prevent="search">
        <input
          v-model="query.keyword"
          class="search-input"
          placeholder="按订单号、联系人或手机号搜索"
        />
        <select v-model="query.status" class="filter-select">
          <option value="">全部订单状态</option>
          <option v-for="opt in ORDER_STATUS_OPTIONS" :key="opt.value" :value="opt.value">
            {{ opt.label }}（{{ opt.value }}）
          </option>
        </select>
        <button type="submit" class="secondary-button" :disabled="loading">查询</button>
        <button
          v-if="query.keyword || query.status"
          type="button"
          class="text-button"
          :disabled="loading"
          @click="resetSearch"
        >清空</button>
      </form>

      <RequestState
        :loading="loading"
        :error="error"
        :empty="!loading && !error && rows.length === 0"
        empty-text="暂无符合筛选条件的订单数据。"
        @retry="load"
      >
        <table class="data-table">
          <thead>
            <tr>
              <th>订单编号</th>
              <th>联系人</th>
              <th>线路 / 团期</th>
              <th>出行人数</th>
              <th>订单总额</th>
              <th>支付状态</th>
              <th>业务状态</th>
              <th>创建时间</th>
              <th style="text-align: right;">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in rows" :key="row.id">
              <td><strong>{{ row.orderNo }}</strong></td>
              <td>
                {{ row.contactName }}
                <div class="muted-text">{{ row.contactPhone }}</div>
              </td>
              <td>
                {{ row.routeName || `线路 #${row.routeId}` }}
                <div class="muted-text">团期 #{{ row.departureId }} · {{ row.departureStartDate }}</div>
              </td>
              <td>{{ Number(row.adultCount || 0) + Number(row.childCount || 0) }} 人</td>
              <td class="amount">¥{{ row.totalAmount }}</td>
              <td>
                <span class="tag" :class="row.paymentStatus === 'PAID' ? 'success' : 'warning'">
                  {{ paymentStatusLabels[row.paymentStatus] || row.paymentStatus }}
                </span>
              </td>
              <td>
                <span class="tag" :class="orderStatusClass(row.status)">
                  {{ orderStatusLabel(row.status) }}
                </span>
              </td>
              <td>{{ row.createdAt }}</td>
              <td style="text-align: right;" class="row-actions">
                <button
                  type="button"
                  class="text-button"
                  :disabled="detailLoadingOrderNo === row.orderNo"
                  @click="openDetail(row)"
                >
                  {{ detailLoadingOrderNo === row.orderNo ? '加载中…' : '详情' }}
                </button>
                <template v-if="row.status === 'PAID_WAIT_CONFIRM'">
                  <span class="divider">|</span>
                  <button
                    type="button"
                    class="text-button text-success"
                    :disabled="confirmingOrderNo === row.orderNo"
                    @click="confirm(row)"
                  >
                    {{ confirmingOrderNo === row.orderNo ? '确认中…' : '确认报名' }}
                  </button>
                </template>
              </td>
            </tr>
          </tbody>
        </table>

        <div class="resource-pager">
          <button type="button" class="secondary-button" :disabled="loading || query.page <= 1" @click="goToPage(-1)">上一页</button>
          <span class="muted-text">第 {{ query.page }} / {{ totalPages || 1 }} 页，共 {{ total }} 条</span>
          <button type="button" class="secondary-button" :disabled="loading || query.page >= totalPages" @click="goToPage(1)">下一页</button>
        </div>
      </RequestState>
    </div>

    <!-- 订单详情弹窗 -->
    <el-dialog
      v-model="detailVisible"
      title="订单详情"
      width="min(860px, calc(100vw - 32px))"
      top="6vh"
      @close="onDetailClose"
    >
      <div v-if="detailLoadingOrderNo"><el-skeleton :rows="8" animated /></div>

      <div v-else-if="detailError" class="request-state" role="alert">
        <strong>暂时无法加载</strong>
        <p>{{ detailError }}</p>
      </div>

      <div v-else-if="detail" class="order-detail">
        <!-- 订单主信息 -->
        <section class="detail-section">
          <div class="section-head">
            <h3>订单信息</h3>
            <span class="tag" :class="orderStatusClass(detail.order.status)">
              {{ orderStatusLabel(detail.order.status) }}
            </span>
          </div>
          <dl class="info-grid">
            <div><dt>订单号</dt><dd>{{ detail.order.orderNo }}</dd></div>
            <div><dt>用户 ID</dt><dd>#{{ detail.order.userId }}</dd></div>
            <div><dt>联系人</dt><dd>{{ detail.order.contactName }}</dd></div>
            <div><dt>联系电话</dt><dd>{{ detail.order.contactPhone }}</dd></div>
            <div><dt>联系邮箱</dt><dd>{{ detail.order.contactEmail || '—' }}</dd></div>
            <div><dt>下单时间</dt><dd>{{ detail.order.createdAt }}</dd></div>
            <div v-if="detail.order.paidAt"><dt>支付时间</dt><dd>{{ detail.order.paidAt }}</dd></div>
            <div v-if="detail.order.confirmedAt"><dt>确认时间</dt><dd>{{ detail.order.confirmedAt }}</dd></div>
            <div v-if="detail.order.cancelledAt"><dt>取消时间</dt><dd>{{ detail.order.cancelledAt }}</dd></div>
            <div v-if="detail.order.completedAt"><dt>完成时间</dt><dd>{{ detail.order.completedAt }}</dd></div>
          </dl>
          <p v-if="detail.order.remark" class="remark"><strong>备注：</strong>{{ detail.order.remark }}</p>
        </section>

        <!-- 线路与团期 -->
        <section class="detail-section">
          <div class="section-head">
            <h3>线路与团期</h3>
          </div>
          <dl class="info-grid">
            <div><dt>线路</dt><dd>{{ detail.route?.name || '—' }}</dd></div>
            <div><dt>出发地 → 目的地</dt><dd>{{ detail.route?.departureCity }} → {{ detail.route?.destination }}</dd></div>
            <div><dt>行程天数</dt><dd>{{ detail.route?.durationDays }} 天</dd></div>
            <div><dt>出发日期</dt><dd>{{ detail.departure?.startDate }}</dd></div>
            <div><dt>返程日期</dt><dd>{{ detail.departure?.endDate }}</dd></div>
            <div><dt>团期状态</dt><dd>{{ detail.departure?.status }}</dd></div>
          </dl>
        </section>

        <!-- 价格与支付 -->
        <section class="detail-section">
          <div class="section-head">
            <h3>价格与支付</h3>
            <span class="tag" :class="detail.payment?.status === 'PAID' ? 'success' : 'warning'">
              {{ paymentStatusLabels[detail.order.paymentStatus] || detail.order.paymentStatus }}
            </span>
          </div>
          <dl class="info-grid">
            <div><dt>成人单价</dt><dd>¥{{ detail.order.adultUnitPrice }} × {{ detail.order.adultCount }}</dd></div>
            <div><dt>儿童单价</dt><dd>¥{{ detail.order.childUnitPrice }} × {{ detail.order.childCount }}</dd></div>
            <div><dt>订单总额</dt><dd class="amount">¥{{ detail.order.totalAmount }}</dd></div>
            <div v-if="detail.payment">
              <dt>支付单号</dt><dd>{{ detail.payment.paymentNo }}</dd>
            </div>
            <div v-if="detail.payment">
              <dt>支付渠道</dt>
              <dd>{{ detail.payment.channel === 'LOCAL_SIMULATION' ? '本地模拟支付（测试）' : detail.payment.channel === 'ALIPAY_SANDBOX' ? '支付宝沙箱' : detail.payment.channel }}</dd>
            </div>
            <div v-if="detail.payment">
              <dt>第三方交易号</dt><dd>{{ detail.payment.thirdPartyTradeNo || '—' }}</dd>
            </div>
          </dl>
          <p v-if="!detail.payment" class="empty-inline">暂无支付记录。</p>
        </section>

        <!-- 出行人 -->
        <section class="detail-section">
          <div class="section-head">
            <h3>出行人（{{ detail.travelers?.length || 0 }}）</h3>
            <span class="privacy-hint">证件号码已脱敏</span>
          </div>
          <table v-if="detail.travelers?.length" class="data-table">
            <thead>
              <tr>
                <th>姓名</th>
                <th>类型</th>
                <th>性别</th>
                <th>出生日期</th>
                <th>证件</th>
                <th>联系电话</th>
                <th>紧急联系人</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="traveler in detail.travelers" :key="traveler.id">
                <td><strong>{{ traveler.name }}</strong></td>
                <td>{{ traveler.travelerType === 'ADULT' ? '成人' : '儿童' }}</td>
                <td>{{ genderLabels[traveler.gender] || traveler.gender }}</td>
                <td>{{ traveler.birthDate }}</td>
                <td>{{ idTypeLabels[traveler.idType] || traveler.idType }} {{ traveler.idNoMasked }}</td>
                <td>{{ traveler.phone || '—' }}</td>
                <td>{{ traveler.emergencyName }} ({{ traveler.emergencyPhone || '—' }})</td>
              </tr>
            </tbody>
          </table>
          <p v-else class="empty-inline">暂无出行人信息。</p>
        </section>

        <!-- 退款记录 -->
        <section v-if="detail.refunds?.length" class="detail-section">
          <div class="section-head">
            <h3>退款记录（{{ detail.refunds.length }}）</h3>
          </div>
          <div class="refund-list">
            <article v-for="refund in detail.refunds" :key="refund.id" class="refund-item">
              <div class="refund-head">
                <strong>¥{{ refund.amount }}</strong>
                <span class="tag" :class="refund.status === 'REFUNDED' ? 'success' : refund.status === 'REJECTED' ? 'danger' : 'warning'">
                  {{ refundStatusLabels[refund.status] || refund.status }}
                </span>
              </div>
              <dl class="info-grid">
                <div><dt>申请原因</dt><dd>{{ refund.reason }}</dd></div>
                <div><dt>申请时间</dt><dd>{{ refund.createdAt }}</dd></div>
                <div v-if="refund.reviewedAt"><dt>审核时间</dt><dd>{{ refund.reviewedAt }}</dd></div>
                <div v-if="refund.reviewComment"><dt>审核意见</dt><dd>{{ refund.reviewComment }}</dd></div>
              </dl>
            </article>
          </div>
        </section>

        <!-- 操作 -->
        <div v-if="detail.order.status === 'PAID_WAIT_CONFIRM'" class="detail-actions">
          <button
            type="button"
            class="primary-button"
            :disabled="confirmingOrderNo === detail.order.orderNo"
            @click="confirm({ orderNo: detail.order.orderNo })"
          >
            {{ confirmingOrderNo === detail.order.orderNo ? '确认中…' : '确认报名' }}
          </button>
        </div>
      </div>
    </el-dialog>
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  gap: 10px;
  margin-bottom: 20px;
  flex-wrap: wrap;
}

.search-input {
  flex: 1;
  min-width: 220px;
  max-width: 420px;
  padding: 7px 10px;
  border: 1px solid var(--border-strong);
  border-radius: 8px;
  background: #fff;
  font-size: 13px;
  color: var(--text-primary);
}

.amount {
  color: var(--price-orange);
  font-weight: 800;
}

.divider {
  color: var(--border-strong);
  margin: 0 6px;
  font-size: 11px;
}

.muted-text {
  color: var(--text-tertiary);
  font-size: 12px;
}

.row-actions {
  white-space: nowrap;
}

.text-success {
  color: var(--success-text) !important;
}

.resource-pager {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 12px;
}

/* ---- 详情弹窗 ---- */
.order-detail {
  display: grid;
  gap: 20px;
}

.detail-section {
  padding: 16px;
  border: 1px solid var(--border-divider, #ececf0);
  border-radius: 12px;
  background: #fbfcfe;
}

.section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 12px;
}

.section-head h3 {
  margin: 0;
  font-size: 14px;
  font-weight: 700;
  color: var(--text-primary);
}

.info-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px 20px;
  margin: 0;
}

.info-grid > div {
  display: grid;
  grid-template-columns: 110px minmax(0, 1fr);
  gap: 8px;
}

.info-grid dt {
  font-size: 12px;
  color: var(--text-tertiary);
}

.info-grid dd {
  margin: 0;
  font-size: 13px;
  color: var(--text-primary);
  overflow-wrap: anywhere;
}

.remark {
  margin: 12px 0 0;
  padding: 10px 12px;
  border-radius: 8px;
  background: #fff;
  font-size: 12px;
  line-height: 1.6;
  color: var(--text-secondary);
}

.privacy-hint {
  font-size: 12px;
  color: var(--text-tertiary);
}

.empty-inline {
  font-size: 12px;
  color: var(--text-tertiary);
}

.refund-list {
  display: grid;
  gap: 12px;
}

.refund-item {
  padding: 12px;
  border-radius: 8px;
  background: #fff;
}

.refund-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 10px;
}

.refund-head strong {
  font-size: 15px;
}

.detail-actions {
  display: flex;
  justify-content: flex-end;
  padding-top: 6px;
}

@media (max-width: 640px) {
  .info-grid {
    grid-template-columns: 1fr;
  }
  .info-grid > div {
    grid-template-columns: 90px minmax(0, 1fr);
  }
}
</style>
