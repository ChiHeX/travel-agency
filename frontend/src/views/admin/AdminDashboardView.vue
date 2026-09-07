<script setup>
import { onMounted, ref } from 'vue'
import { adminApi } from '@/api/modules'

const loading = ref(false)
const metrics = ref(null)

onMounted(async () => {
  loading.value = true
  try {
    metrics.value = await adminApi.dashboard()
  } finally {
    loading.value = false
  }
})

const money = (value) => (value == null ? '¥0.00' : `¥${Number(value).toFixed(2)}`)
</script>

<template>
  <div class="dashboard-page">
    <div class="admin-page-head">
      <div>
        <h2>运营数据概览</h2>
        <p>真实联调时展示数据库中的订单、报名人次与团期统计；契约 Mock 模式仅用于页面开发。</p>
      </div>
      <span class="tag success">● API 实时数据</span>
    </div>

    <div v-if="loading" class="stats-grid">
      <div v-for="i in 8" :key="i" class="stat-card">
        <el-skeleton :rows="2" animated />
      </div>
    </div>

    <div v-else-if="metrics" class="stats-grid">
      <div class="stat-card">
        <span class="label">平台注册用户</span>
        <div class="value">{{ metrics.userCount }}</div>
        <span class="hint">已验证游客账号</span>
      </div>

      <div class="stat-card">
        <span class="label">已上架线路</span>
        <div class="value">{{ metrics.publishedRouteCount }}</div>
        <span class="hint">可供游客浏览</span>
      </div>

      <div class="stat-card">
        <span class="label">开放报名团期</span>
        <div class="value">{{ metrics.openDepartureCount }}</div>
        <span class="hint">状态 OPEN 待出行</span>
      </div>

      <div class="stat-card">
        <span class="label">今日新增订单</span>
        <div class="value">{{ metrics.todayOrderCount }}</div>
        <span class="hint">今日创建的有效单</span>
      </div>

      <div class="stat-card">
        <span class="label">待确认订单</span>
        <div class="value text-warning">{{ metrics.pendingConfirmCount }}</div>
        <span class="hint">需工作人员手动确认</span>
      </div>

      <div class="stat-card">
        <span class="label">待处理退款申请</span>
        <div class="value text-danger">{{ metrics.pendingRefundCount }}</div>
        <span class="hint">需财务/客服审核</span>
      </div>

      <div class="stat-card">
        <span class="label">累计出行总人次</span>
        <div class="value">{{ metrics.participantCount }}</div>
        <span class="hint">已排除取消与退款</span>
      </div>

      <div class="stat-card highlight-metric">
        <span class="label">已支付订单总额</span>
        <div class="value money-figure">{{ money(metrics.grossOrderAmount) }}</div>
        <span class="hint">支付流水汇总</span>
      </div>
    </div>

    <div v-else class="empty-box">
      暂无统计数据，请确认后端数据库连接正常。
    </div>

    <div class="dashboard-grid">
      <div class="admin-panel analytics-empty">
        <span class="eyebrow">ORDER TRENDS</span>
        <h3>订单趋势</h3>
        <p>后端提供按日或按周聚合数据后，此处显示趋势图。</p>
      </div>
      <div class="admin-panel analytics-empty">
        <span class="eyebrow">POPULAR DESTINATIONS</span>
        <h3>热门目的地</h3>
        <p>后端提供有效订单聚合数据后，此处显示目的地排行。</p>
      </div>
    </div>
  </div>
</template>

<style scoped>
.text-warning {
  color: var(--warning-text) !important;
}

.text-danger {
  color: var(--danger-text) !important;
}

.highlight-metric {
  border-color: var(--brand-blue-tint);
  background: radial-gradient(circle at 100% 0%, #eff6ff 0%, #ffffff 70%);
}

.money-figure {
  color: var(--price-orange) !important;
  font-size: 24px !important;
}

.dashboard-grid {
  display: grid;
  grid-template-columns: 1.3fr 1fr;
  gap: 20px;
}

.analytics-empty {
  min-height: 220px;
  display: flex;
  flex-direction: column;
  justify-content: center;
}

.analytics-empty h3 {
  margin: 6px 0 8px;
  color: var(--text-primary);
  font-size: 16px;
}

.analytics-empty p {
  margin: 0;
  color: var(--text-tertiary);
  font-size: 12px;
  line-height: 1.6;
}

.panel-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 20px;
}

.panel-head h3 {
  font-size: 16px;
  font-weight: 700;
  color: var(--text-primary);
  margin: 2px 0 0;
}

.chart-visual-mock {
  position: relative;
  background: var(--bg-subtle);
  border-radius: var(--radius-md);
  padding: 16px 16px 8px;
  overflow: hidden;
}

.trend-svg {
  display: block;
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
  grid-template-columns: 20px 1fr 60px;
  align-items: center;
  gap: 12px;
}

.rank-num {
  font-size: 13px;
  font-weight: 800;
  color: var(--brand-blue);
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
  background: var(--bg-subtle);
  border-radius: var(--radius-full);
  overflow: hidden;
}

.rank-bar {
  height: 100%;
  background: var(--brand-blue);
  border-radius: var(--radius-full);
}

.rank-sales {
  text-align: right;
  font-size: 12px;
  color: var(--text-secondary);
}

@media (max-width: 900px) {
  .dashboard-grid {
    grid-template-columns: 1fr;
  }
}
</style>
