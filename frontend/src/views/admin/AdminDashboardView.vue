<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { adminApi } from '@/api/modules'

/**
 * 后台工作台（Dashboard 数据统计）。
 *
 * <p>页面不做任何本地造数：标量指标直接渲染契约 {@code GET /admin/dashboard} 的
 * {@code DashboardData}，四个数组（{@code orderTrend} / {@code popularRoutes} /
 * {@code popularDestinations} / {@code departureEnrollment}）此前都没有被页面消费
 * （只留了「后端提供数据后此处显示」的占位文案），这里补齐趋势图、两个排行与团期报名情况。
 * 契约里 {@code days} 只控制 {@code orderTrend} 的窗口长度，窗口内缺失日期由后端补零。</p>
 */

/** 契约把 days 声明为 enum [7, 30]，默认 7。 */
const DAY_OPTIONS = [7, 30]

const days = ref(7)
const metrics = ref(null)
const loading = ref(false)
const errorMessage = ref('')

/**
 * 丢弃过期响应：切换窗口时先发的请求可能后回来，直接用它的结果会覆盖新窗口的数据，
 * 出现「点了近 30 天、图却是近 7 天」的错配。
 */
let requestSeq = 0

async function load() {
  const seq = ++requestSeq
  loading.value = true
  errorMessage.value = ''
  try {
    const data = await adminApi.dashboard({ days: days.value })
    if (seq !== requestSeq) return
    metrics.value = data
  } catch (error) {
    if (seq !== requestSeq) return
    errorMessage.value = error?.message || '统计数据加载失败，请稍后重试。'
  } finally {
    if (seq === requestSeq) loading.value = false
  }
}

onMounted(load)
watch(days, load)

const money = (value) => (value == null || value === '' ? '¥0.00' : `¥${Number(value).toFixed(2)}`)
const count = (value) => value ?? 0
const monthDay = (date) => (typeof date === 'string' ? date.slice(5) : '')

const trend = computed(() => metrics.value?.orderTrend ?? [])
const popularRoutes = computed(() => metrics.value?.popularRoutes ?? [])
const popularDestinations = computed(() => metrics.value?.popularDestinations ?? [])
const departureEnrollment = computed(() => metrics.value?.departureEnrollment ?? [])

/** 窗口合计由返回的序列累加得到；金额按分累加，避免浮点误差。 */
const trendTotals = computed(() => trend.value.reduce((sum, item) => ({
  orderCount: sum.orderCount + count(item.orderCount),
  participantCount: sum.participantCount + count(item.participantCount),
  amountCents: sum.amountCents + Math.round(Number(item.orderAmount ?? 0) * 100)
}), { orderCount: 0, participantCount: 0, amountCents: 0 }))

const trendPeak = computed(() => Math.max(1, ...trend.value.map((item) => count(item.orderCount))))

/** 固定 viewBox + 自适应柱宽：7 天柱子粗一些，30 天自动收窄。 */
const CHART = { width: 720, height: 200, top: 12, bottom: 30, left: 8, right: 8 }

const trendBars = computed(() => {
  const items = trend.value
  if (!items.length) return []
  const innerWidth = CHART.width - CHART.left - CHART.right
  const innerHeight = CHART.height - CHART.top - CHART.bottom
  const step = innerWidth / items.length
  const barWidth = Math.min(step * 0.6, 30)
  // 30 天时逐日标注会糊成一片，改为每 5 天一个刻度；首尾始终标出。
  const labelStep = items.length > 10 ? 5 : 1
  return items.map((item, index) => {
    const value = count(item.orderCount)
    const height = value === 0 ? 0 : Math.max(2, (value / trendPeak.value) * innerHeight)
    return {
      date: item.date,
      x: CHART.left + step * index + (step - barWidth) / 2,
      y: CHART.top + innerHeight - height,
      width: barWidth,
      height,
      label: monthDay(item.date),
      labelX: CHART.left + step * (index + 0.5),
      showLabel: index % labelStep === 0 || index === items.length - 1,
      tooltip: `${item.date}：订单 ${value} 单 · 报名 ${count(item.participantCount)} 人次 · 已支付 ${money(item.orderAmount)}`
    }
  })
})

const destinationPeak = computed(() =>
  Math.max(1, ...popularDestinations.value.map((item) => count(item.validBookingCount))))

/** 排行条宽度：0 不画，其余至少 4% 以保证可见。 */
const barWidth = (value, peak) => {
  const ratio = count(value) / peak
  return ratio <= 0 ? '0%' : `${Math.max(4, Math.round(ratio * 100))}%`
}

/**
 * 团期名额占用比例（已确认 + 待确认）。上限 100%：历史脏数据里已占用可能超过名额，
 * 后端已把 remainingSeats 钳在 0，进度条同样不能画出超过一整条。
 */
const enrolledPercent = (row) => {
  const max = count(row.maxPeople)
  if (max <= 0) return '0%'
  const used = count(row.confirmedPeople) + count(row.reservedPeople)
  return `${Math.min(100, Math.round((used / max) * 100))}%`
}
</script>

<template>
  <div class="dashboard-page">
    <div class="admin-page-head">
      <div>
        <h2>运营数据概览</h2>
        <p>全部指标由后端按数据库实时统计（GET /admin/dashboard），没有数据时显示空状态。</p>
      </div>
      <span class="tag success">● 数据库实时统计</span>
    </div>

    <div v-if="loading && !metrics" class="stats-grid">
      <div v-for="i in 8" :key="i" class="stat-card">
        <el-skeleton :rows="2" animated />
      </div>
    </div>

    <div v-else-if="metrics" class="stats-grid">
      <div class="stat-card">
        <span class="label">平台注册账号</span>
        <div class="value">{{ metrics.userCount }}</div>
        <span class="hint">全部未删除账号，含游客与员工</span>
      </div>

      <div class="stat-card">
        <span class="label">已上架线路</span>
        <div class="value">{{ metrics.publishedRouteCount }}</div>
        <span class="hint">状态 PUBLISHED</span>
      </div>

      <div class="stat-card">
        <span class="label">可报名团期</span>
        <div class="value">{{ metrics.openDepartureCount }}</div>
        <span class="hint">OPEN 且未过出发日期</span>
      </div>

      <div class="stat-card">
        <span class="label">今日新增订单</span>
        <div class="value">{{ metrics.todayOrderCount }}</div>
        <span class="hint">今日创建的全部订单</span>
      </div>

      <div class="stat-card">
        <span class="label">待确认订单</span>
        <div class="value text-warning">{{ metrics.pendingConfirmCount }}</div>
        <span class="hint">状态 PAID_WAIT_CONFIRM</span>
      </div>

      <div class="stat-card">
        <span class="label">待处理退款申请</span>
        <div class="value text-danger">{{ metrics.pendingRefundCount }}</div>
        <span class="hint">状态 REFUND_APPLYING 待审核</span>
      </div>

      <div class="stat-card">
        <span class="label">累计报名人次</span>
        <div class="value">{{ metrics.participantCount }}</div>
        <span class="hint">已排除取消与退款订单</span>
      </div>

      <div class="stat-card highlight-metric">
        <span class="label">已支付订单总额</span>
        <div class="value money-figure">{{ money(metrics.grossOrderAmount) }}</div>
        <span class="hint">已支付且未退款的订单合计</span>
      </div>
    </div>

    <div v-if="errorMessage" class="empty-box dashboard-error">
      <p>{{ errorMessage }}</p>
      <button type="button" class="secondary-button" :disabled="loading" @click="load">重新加载</button>
    </div>

    <div v-if="metrics" class="dashboard-grid">
      <div class="admin-panel">
        <div class="panel-head">
          <div>
            <span class="eyebrow">ORDER TRENDS</span>
            <h3>订单趋势</h3>
          </div>
          <div class="panel-tools">
            <span v-if="loading" class="loading-hint">更新中…</span>
            <div class="window-switch" role="group" aria-label="订单趋势统计窗口">
              <button
                v-for="option in DAY_OPTIONS"
                :key="option"
                type="button"
                class="window-button"
                :class="{ active: days === option }"
                :aria-pressed="days === option"
                @click="days = option"
              >
                近 {{ option }} 天
              </button>
            </div>
          </div>
        </div>

        <div v-if="loading" class="empty-box trend-state" role="status">
          正在加载近 {{ days }} 天订单趋势…
        </div>
        <div v-else-if="errorMessage" class="empty-box trend-state">
          近 {{ days }} 天订单趋势加载失败，请重新加载。
        </div>
        <div v-else-if="trend.length" class="chart-visual">
          <svg
            class="trend-svg"
            :viewBox="`0 0 ${CHART.width} ${CHART.height}`"
            role="img"
            :aria-label="`近 ${days} 天订单趋势：共 ${trendTotals.orderCount} 单、报名 ${trendTotals.participantCount} 人次、已支付 ${money(trendTotals.amountCents / 100)}`"
          >
            <line
              :x1="CHART.left"
              :x2="CHART.width - CHART.right"
              :y1="CHART.height - CHART.bottom"
              :y2="CHART.height - CHART.bottom"
              class="chart-axis"
            />
            <g v-for="bar in trendBars" :key="bar.date">
              <rect
                v-if="bar.height > 0"
                class="chart-bar"
                :x="bar.x"
                :y="bar.y"
                :width="bar.width"
                :height="bar.height"
                rx="3"
              >
                <title>{{ bar.tooltip }}</title>
              </rect>
              <text
                v-if="bar.showLabel"
                class="chart-label"
                :x="bar.labelX"
                :y="CHART.height - 12"
                text-anchor="middle"
              >{{ bar.label }}</text>
            </g>
          </svg>
          <div class="chart-legend">
            窗口内合计：订单 {{ trendTotals.orderCount }} 单 · 报名 {{ trendTotals.participantCount }} 人次 ·
            已支付 {{ money(trendTotals.amountCents / 100) }}
          </div>
          <p v-if="trendTotals.orderCount === 0" class="chart-legend">该窗口内没有订单记录。</p>
        </div>

        <div v-else class="empty-box">该窗口内没有订单记录。</div>
      </div>

      <div class="admin-panel">
        <div class="panel-head">
          <div>
            <span class="eyebrow">POPULAR DESTINATIONS</span>
            <h3>热门目的地</h3>
          </div>
        </div>

        <div v-if="popularDestinations.length" class="dest-ranking-list">
          <div v-for="(item, index) in popularDestinations" :key="item.destination" class="ranking-row">
            <span class="rank-num">{{ index + 1 }}</span>
            <div class="rank-info">
              <span class="rank-name">{{ item.destination }}</span>
              <div class="rank-bar-wrap">
                <div class="rank-bar" :style="{ width: barWidth(item.validBookingCount, destinationPeak) }"></div>
              </div>
            </div>
            <span class="rank-sales">{{ item.validBookingCount }} 单</span>
          </div>
        </div>

        <div v-else class="empty-box">暂无有效报名数据。</div>
      </div>
    </div>

    <div v-if="metrics" class="admin-panel enrollment-panel">
      <div class="panel-head">
        <div>
          <span class="eyebrow">DEPARTURE ENROLLMENT</span>
          <h3>团期报名情况</h3>
        </div>
        <span class="panel-note">未来最近 5 个仍在销售的团期</span>
      </div>

      <div v-if="departureEnrollment.length" class="enrollment-grid">
        <div v-for="row in departureEnrollment" :key="row.departureId" class="enrollment-card">
          <RouterLink class="enrollment-route" :to="{ name: 'admin-route-detail', params: { id: row.routeId } }">
            {{ row.routeName }}
          </RouterLink>
          <span class="enrollment-date">出发 {{ row.startDate }}</span>
          <div class="enrollment-bar-wrap">
            <div class="enrollment-bar" :style="{ width: enrolledPercent(row) }"></div>
          </div>
          <div class="enrollment-foot">
            <span class="enrollment-count">
              已确认 {{ row.confirmedPeople }} / {{ row.maxPeople }} 人
              <template v-if="row.reservedPeople">· 待确认 {{ row.reservedPeople }} 人</template>
            </span>
            <span class="tag" :class="row.remainingSeats > 0 ? 'success' : 'danger'">
              {{ row.remainingSeats > 0 ? `剩余 ${row.remainingSeats} 位` : '名额已满' }}
            </span>
          </div>
        </div>
      </div>

      <div v-else class="empty-box">暂无未出发的开放团期。</div>
    </div>

    <div v-if="metrics" class="admin-panel popular-route-panel">
      <div class="panel-head">
        <div>
          <span class="eyebrow">POPULAR ROUTES</span>
          <h3>热门线路</h3>
        </div>
      </div>

      <div v-if="popularRoutes.length" class="popular-route-list">
        <RouterLink
          v-for="(route, index) in popularRoutes"
          :key="route.id"
          class="popular-route-row"
          :to="{ name: 'admin-route-detail', params: { id: route.id } }"
        >
          <span class="rank-num">{{ index + 1 }}</span>
          <div class="popular-route-info">
            <strong>{{ route.name }}</strong>
            <span>
              {{ route.destination }} · {{ route.durationDays }} 天 · 评分 {{ route.ratingAvg }}（{{ route.ratingCount }} 条）
            </span>
          </div>
          <span class="rank-sales">有效报名 {{ route.validBookingCount }} 单</span>
        </RouterLink>
      </div>

      <div v-else class="empty-box">暂无已成交线路。</div>
    </div>
  </div>
</template>

<style scoped>
/*
 * 只保留工作台自己的样式：后台框架（.admin-shell/.admin-sidebar/.admin-topbar/.admin-content）、
 * 页面标题区（.admin-page-head）与指标卡（.stats-grid/.stat-card）都是多个后台页面共用的，
 * 已在 styles/global.css 的「Admin Workspace」一节统一定义（它们的样式块曾被 b2fc15e 删掉，
 * 这里不再各自复制一份）。
 */
.text-warning {
  color: var(--status-orange) !important;
}

.text-danger {
  color: var(--status-red) !important;
}

.highlight-metric {
  border-color: var(--theme-blue-tint);
  background: radial-gradient(circle at 100% 0%, #eff6ff 0%, #ffffff 70%);
}

.money-figure {
  color: var(--price-color) !important;
  font-size: 24px !important;
}

.dashboard-error {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 14px;
  margin-bottom: 22px;
}

/* 趋势图的加载/失败提示：占住图表的高度，切换窗口时页面不会跳动。 */
.trend-state {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 232px;
}

.dashboard-error p {
  margin: 0;
}

.dashboard-grid {
  display: grid;
  grid-template-columns: 1.3fr 1fr;
  gap: 20px;
}

.popular-route-panel {
  margin-top: 20px;
}

.enrollment-panel {
  margin-top: 20px;
}

.panel-note {
  color: var(--text-tertiary);
  font-size: 11px;
}

.enrollment-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 14px;
}

.enrollment-card {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 14px;
  border: 1px solid var(--border-divider);
  border-radius: var(--radius-sm);
}

.enrollment-route {
  color: var(--text-primary);
  font-size: 13px;
  font-weight: 600;
  text-decoration: none;
}

.enrollment-route:hover {
  color: var(--theme-blue);
  text-decoration: underline;
}

.enrollment-date {
  color: var(--text-secondary);
  font-size: 11px;
}

.enrollment-bar-wrap {
  height: 6px;
  background: var(--bg-secondary);
  border-radius: var(--radius-full);
  overflow: hidden;
}

.enrollment-bar {
  height: 100%;
  background: var(--theme-blue);
  border-radius: var(--radius-full);
}

.enrollment-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.enrollment-count {
  color: var(--text-secondary);
  font-size: 11px;
}

.panel-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
  margin-bottom: 20px;
}

.panel-head h3 {
  font-size: 16px;
  font-weight: 700;
  color: var(--text-primary);
  margin: 2px 0 0;
}

.panel-tools {
  display: flex;
  align-items: center;
  gap: 10px;
}

.loading-hint {
  color: var(--text-tertiary);
  font-size: 11px;
}

.window-switch {
  display: inline-flex;
  padding: 2px;
  gap: 2px;
  background: var(--bg-secondary);
  border-radius: var(--radius-full);
}

.window-button {
  border: none;
  background: transparent;
  color: var(--text-secondary);
  font-size: 12px;
  font-weight: 600;
  padding: 5px 12px;
  border-radius: var(--radius-full);
  cursor: pointer;
}

.window-button.active {
  background: #ffffff;
  color: var(--theme-blue);
  box-shadow: var(--shadow-subtle);
}

.chart-visual {
  background: var(--bg-secondary);
  border-radius: var(--radius-md);
  padding: 16px 16px 8px;
}

.trend-svg {
  display: block;
  width: 100%;
  height: auto;
}

.chart-axis {
  stroke: var(--border-divider);
  stroke-width: 1;
}

.chart-bar {
  fill: var(--theme-blue);
}

.chart-label {
  fill: var(--text-tertiary);
  font-size: 11px;
}

.chart-legend {
  padding-top: 10px;
  font-size: 11px;
  color: var(--text-tertiary);
  text-align: center;
}

.dest-ranking-list {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.ranking-row {
  display: grid;
  grid-template-columns: 20px 1fr 72px;
  align-items: center;
  gap: 12px;
}

.rank-num {
  font-size: 13px;
  font-weight: 800;
  color: var(--theme-blue);
}

.rank-info {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.rank-name {
  font-size: 12px;
  font-weight: 600;
  color: var(--text-primary);
}

.rank-bar-wrap {
  height: 6px;
  background: var(--bg-secondary);
  border-radius: var(--radius-full);
  overflow: hidden;
}

.rank-bar {
  height: 100%;
  background: var(--theme-blue);
  border-radius: var(--radius-full);
}

.rank-sales {
  text-align: right;
  font-size: 12px;
  color: var(--text-secondary);
}

.popular-route-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.popular-route-row {
  display: grid;
  grid-template-columns: 20px 1fr auto;
  align-items: center;
  gap: 12px;
  padding: 12px 14px;
  border: 1px solid var(--border-divider);
  border-radius: var(--radius-sm);
  text-decoration: none;
  transition: border-color 0.15s ease, background 0.15s ease;
}

.popular-route-row:hover {
  border-color: var(--theme-blue);
  background: var(--bg-secondary);
}

.popular-route-info {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}

.popular-route-info strong {
  color: var(--text-primary);
  font-size: 13px;
}

.popular-route-info span {
  color: var(--text-tertiary);
  font-size: 11px;
}

@media (max-width: 900px) {
  .stats-grid {
    grid-template-columns: repeat(2, 1fr);
  }

  .dashboard-grid {
    grid-template-columns: 1fr;
  }

  .popular-route-row {
    grid-template-columns: 20px 1fr;
  }

  .popular-route-row .rank-sales {
    grid-column: 2;
    text-align: left;
  }
}
</style>
