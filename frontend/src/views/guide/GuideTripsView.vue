<script setup>
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { guideApi } from '@/api/modules'

const props = defineProps({ detail: { type: Boolean, default: false } })
const currentRoute = useRoute()
const rows = ref([])
const data = ref(null)
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    if (props.detail) {
      const [detail, passengers] = await Promise.all([
        guideApi.detail(currentRoute.params.id),
        guideApi.passengers(currentRoute.params.id)
      ])
      data.value = { ...detail, passengers }
    } else {
      rows.value = (await guideApi.departures())?.items || []
    }
  } finally {
    loading.value = false
  }
}

async function markFinished() {
  await guideApi.complete(data.value.departure.id)
  ElMessage.success('团期已顺利标记为完成')
  load()
}

onMounted(load)
</script>

<template>
  <div class="guide-trips-page">
    <template v-if="props.detail">
      <div class="admin-page-head">
        <div>
          <h2>带团详情与游客名单</h2>
          <p>核对集合情况，仅展示出团必须的游客联系与脱敏信息。</p>
        </div>
        <button
          v-if="data?.departure?.status === 'TRAVELLING'"
          type="button"
          class="primary-button"
          @click="markFinished"
        >
          标记行程已结束
        </button>
      </div>

      <div v-if="loading" class="admin-panel">
        <el-skeleton :rows="8" animated />
      </div>

      <template v-else-if="data">
        <div class="admin-panel trip-hero-card">
          <span class="eyebrow">SCHEDULE #{{ data.departure.id }}</span>
          <h3>跟团线路编号 #{{ data.departure.routeId }}</h3>
          <div class="trip-meta-tags">
            <span>出团日期：{{ data.departure.startDate }} 至 {{ data.departure.endDate }}</span>
            <span>·</span>
            <span>当前状态：<strong class="tag success">{{ data.departure.status }}</strong></span>
          </div>
        </div>

        <div class="admin-panel">
          <div class="panel-head-flex">
            <div>
              <span class="eyebrow">PASSENGERS</span>
              <h3>本团实名游客名单 (共 {{ data.passengers?.length || 0 }} 人)</h3>
            </div>
            <span class="privacy-hint">严格受限于出团服务用途 · 证件信息已脱敏</span>
          </div>

          <table v-if="data.passengers?.length" class="data-table">
            <thead>
              <tr>
                <th>游客姓名</th>
                <th>联系电话</th>
                <th>紧急联系人与电话</th>
                <th>证件类型与脱敏号</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="item in data.passengers" :key="`${item.orderNo}-${item.name}`">
                <td><strong>{{ item.name }}</strong></td>
                <td>{{ item.phone || '—' }}</td>
                <td>{{ item.emergencyName }} ({{ item.emergencyPhone || '—' }})</td>
                <td>{{ item.idNo }}</td>
              </tr>
            </tbody>
          </table>

          <div v-else class="empty-box">
            本团期暂无已报名的实名游客。
          </div>
        </div>
      </template>
    </template>

    <template v-else>
      <div class="admin-page-head">
        <div>
          <h2>我的全部带团班次</h2>
          <p>按出行日期管理历史与未来所有由您负责的跟团班次。</p>
        </div>
      </div>

      <div v-if="loading" class="admin-panel">
        <el-skeleton :rows="8" animated />
      </div>

      <div v-else-if="rows.length" class="trips-grid">
        <RouterLink
          v-for="trip in rows"
          :key="trip.id"
          class="admin-panel trip-grid-card"
          :to="{ name: 'guide-trip-detail', params: { id: trip.id } }"
        >
          <div class="card-status-bar">
            <span class="tag" :class="trip.status === 'TRAVELLING' ? 'warning' : 'success'">
              {{ trip.status }}
            </span>
            <span class="trip-id-text">团期 #{{ trip.id }}</span>
          </div>

          <h3>跟团线路 #{{ trip.routeId }}</h3>
          <p class="trip-dates-text">{{ trip.startDate }} 至 {{ trip.endDate }}</p>

          <div class="card-foot-link">
            <span class="plain-link">查看游客名单 →</span>
          </div>
        </RouterLink>
      </div>

      <div v-else class="empty-box">
        暂无分配给您的带团排期记录。
      </div>
    </template>
  </div>
</template>

<style scoped>
.trip-hero-card {
  margin-bottom: 20px;
}

.trip-hero-card h3 {
  font-size: 20px;
  font-weight: 700;
  color: var(--text-primary);
  margin: 4px 0 8px;
}

.trip-meta-tags {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: var(--text-secondary);
}

.panel-head-flex {
  display: flex;
  justify-content: space-between;
  align-items: flex-end;
  margin-bottom: 20px;
}

.panel-head-flex h3 {
  font-size: 16px;
  font-weight: 700;
  margin: 2px 0 0;
  color: var(--text-primary);
}

.privacy-hint {
  font-size: 12px;
  color: var(--text-tertiary);
}

.trips-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 20px;
}

.trip-grid-card {
  display: flex;
  flex-direction: column;
  transition: all 0.2s ease;
}

.trip-grid-card:hover {
  transform: translateY(-3px);
  border-color: var(--brand-blue);
  box-shadow: var(--shadow-md);
}

.card-status-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}

.trip-id-text {
  font-size: 12px;
  color: var(--text-tertiary);
}

.trip-grid-card h3 {
  font-size: 16px;
  font-weight: 700;
  color: var(--text-primary);
  margin: 0 0 6px;
}

.trip-dates-text {
  font-size: 13px;
  color: var(--text-secondary);
  margin: 0 0 16px;
  flex: 1;
}

.card-foot-link {
  padding-top: 12px;
  border-top: 1px solid var(--border-line);
}

@media (max-width: 900px) {
  .trips-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}

@media (max-width: 600px) {
  .trips-grid {
    grid-template-columns: 1fr;
  }
}
</style>
