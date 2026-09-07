<script setup>
import { onMounted, ref } from 'vue'
import { guideApi } from '@/api/modules'

const data = ref({ upcoming: [], current: [], history: [] })
const loading = ref(false)

onMounted(async () => {
  loading.value = true
  try {
    data.value = (await guideApi.dashboard()) || data.value
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div class="guide-dashboard-page">
    <div class="admin-page-head">
      <div>
        <h2>导游带团工作台</h2>
        <p>仅展示您名下负责的排期团次与出团必要游客信息，严格保障游客隐私安全。</p>
      </div>
      <span class="tag primary">认证专属导游</span>
    </div>

    <!-- Stat Metrics -->
    <div class="stats-grid">
      <div class="stat-card">
        <span class="label">即将出发班期</span>
        <div class="value">{{ data.upcoming.length }}</div>
        <span class="hint">未来 7 天内出团</span>
      </div>

      <div class="stat-card">
        <span class="label">当前正在带团</span>
        <div class="value text-warning">{{ data.current.length }}</div>
        <span class="hint">状态 TRAVELLING</span>
      </div>

      <div class="stat-card">
        <span class="label">累计带团履约</span>
        <div class="value text-success">{{ data.history.length }}</div>
        <span class="hint">已顺利完成</span>
      </div>
    </div>

    <!-- Upcoming List -->
    <div v-if="loading" class="admin-panel">
      <el-skeleton :rows="6" animated />
    </div>

    <div v-else class="admin-panel">
      <div class="panel-head">
        <div>
          <span class="eyebrow">UPCOMING TRIPS</span>
          <h3>即将出发的跟团班次</h3>
        </div>
      </div>

      <div v-if="data.upcoming.length" class="trip-list">
        <div v-for="trip in data.upcoming" :key="trip.id" class="trip-row-item">
          <div class="trip-dates">
            <strong>{{ trip.startDate }}</strong>
            <span class="arrow">至</span>
            <strong>{{ trip.endDate }}</strong>
          </div>
          <div class="trip-route-id">
            <span>跟团线路编号 #{{ trip.routeId }}</span>
          </div>
          <span class="tag success">{{ trip.status }}</span>
          <RouterLink
            class="primary-button small-btn"
            :to="{ name: 'guide-trip-detail', params: { id: trip.id } }"
          >
            查看游客名单 →
          </RouterLink>
        </div>
      </div>

      <div v-else class="empty-box">
        近期暂无即将出发的带团安排。
      </div>
    </div>
  </div>
</template>

<style scoped>
.text-warning {
  color: var(--warning-text) !important;
}

.text-success {
  color: var(--success-text) !important;
}

.panel-head h3 {
  font-size: 16px;
  font-weight: 700;
  color: var(--text-primary);
  margin: 2px 0 16px;
}

.trip-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.trip-row-item {
  display: grid;
  grid-template-columns: 1.2fr 1fr auto auto;
  align-items: center;
  gap: 16px;
  padding: 16px 20px;
  background: var(--bg-subtle);
  border: 1px solid var(--border-line);
  border-radius: var(--radius-md);
  font-size: 13px;
  transition: all 0.15s ease;
}

.trip-row-item:hover {
  background: white;
  border-color: var(--brand-blue);
  box-shadow: var(--shadow-sm);
}

.trip-dates {
  display: flex;
  align-items: center;
  gap: 8px;
}

.trip-dates strong {
  color: var(--text-primary);
}

.trip-dates .arrow {
  color: var(--text-tertiary);
  font-size: 12px;
}

.trip-route-id span {
  color: var(--text-secondary);
  font-weight: 500;
}

.small-btn {
  min-height: 32px;
  padding: 0 14px;
  font-size: 12px;
}

@media (max-width: 768px) {
  .trip-row-item {
    grid-template-columns: 1fr;
    gap: 10px;
  }
}
</style>
