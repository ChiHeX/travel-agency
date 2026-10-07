<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { guideApi } from '@/api/modules'

const props = defineProps({ detail: { type: Boolean, default: false } })
const currentRoute = useRoute()
const rows = ref([])
const data = ref(null)
const loading = ref(false)
const pending = ref(false)

/**
 * 契约 POST /guide/departures/{departureId}/start 允许的前置状态
 * （与后端 `DepartureService#STARTABLE_STATUSES` 一致）：已开售 / 已满 / 已截止，
 * 都还没出发。DRAFT 刻意不在其中——草稿团期还没上架，后端会返回 409，
 * 因此按钮也不该出现，避免导游点进去才发现不能开始。
 */
const STARTABLE_STATUSES = ['OPEN', 'FULL', 'CLOSED']

const canStart = computed(() => STARTABLE_STATUSES.includes(data.value?.departure?.status))
const canComplete = computed(() => data.value?.departure?.status === 'TRAVELLING')

const itemTypeNames = {
  ATTRACTION: '景点', TRANSPORT: '交通', MEAL: '餐食', ACTIVITY: '活动', OTHER: '其他'
}

async function load() {
  loading.value = true
  try {
    if (props.detail) {
      // 契约：GET /guide/departures/{id} 返回 { departure, route, itinerary }；
      // 游客名单由 /passengers 独立接口提供，两者并行请求。
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

/**
 * 团期状态推进：先调接口，再按结果提示并重新拉取，保证页面显示的是服务端真实状态。
 *
 * <p>重复点击用 `pending` 挡下：后端的状态机是条件 UPDATE，第二次请求只会拿到 409，
 * 让用户看到一条无意义的冲突提示。</p>
 */
async function transition(action, successText) {
  if (pending.value) return
  pending.value = true
  try {
    await action(data.value.departure.id)
    ElMessage.success(successText)
  } catch (cause) {
    ElMessage.error(cause?.message || '操作失败，请稍后重试')
  } finally {
    pending.value = false
    await load()
  }
}

/** 开始行程：OPEN / FULL / CLOSED → TRAVELLING，同时把该团期订单级联为在途。 */
async function startTrip() {
  await transition(guideApi.start, '行程已开始，祝带团顺利')
}

async function markFinished() {
  await transition(guideApi.complete, '团期已顺利标记为完成')
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
        <div class="trip-actions">
          <button
            v-if="canStart"
            type="button"
            class="primary-button"
            :disabled="pending"
            @click="startTrip"
          >
            {{ pending ? '处理中…' : '开始行程' }}
          </button>
          <button
            v-if="canComplete"
            type="button"
            class="primary-button"
            :disabled="pending"
            @click="markFinished"
          >
            {{ pending ? '处理中…' : '标记行程已结束' }}
          </button>
        </div>
      </div>

      <div v-if="loading" class="admin-panel">
        <el-skeleton :rows="8" animated />
      </div>

      <template v-else-if="data">
        <div class="admin-panel trip-hero-card">
          <span class="eyebrow">SCHEDULE #{{ data.departure.id }}</span>
          <h3>{{ data.route?.name || `跟团线路 #${data.departure.routeId}` }}</h3>
          <div class="trip-meta-tags">
            <span v-if="data.route">{{ data.route.departureCity }} → {{ data.route.destination }} · {{ data.route.durationDays }} 天</span>
            <span>·</span>
            <span>出团日期：{{ data.departure.startDate }} 至 {{ data.departure.endDate }}</span>
            <span>·</span>
            <span>当前状态：<strong class="tag success">{{ data.departure.status }}</strong></span>
          </div>
        </div>

        <div class="admin-panel">
          <div class="panel-head-flex">
            <div>
              <span class="eyebrow">ITINERARY</span>
              <h3>出团每日行程 (共 {{ data.itinerary?.length || 0 }} 天)</h3>
            </div>
            <span class="privacy-hint">按线路维护的行程安排，供带团执行时核对</span>
          </div>

          <div v-if="data.itinerary?.length" class="day-list">
            <article v-for="day in data.itinerary" :key="day.id" class="day-card">
              <header class="day-head">
                <strong>第 {{ day.dayNumber }} 天 · {{ day.title }}</strong>
                <div class="day-meta">
                  <span>交通：{{ day.transportation || '未填写' }}</span>
                  <span>餐食：{{ day.meals || '未填写' }}</span>
                  <span>酒店：{{ day.hotelName || '未安排' }}</span>
                </div>
              </header>
              <p v-if="day.description" class="day-description">{{ day.description }}</p>

              <table v-if="day.items?.length" class="data-table inner-table">
                <thead>
                  <tr>
                    <th style="width: 70px;">排序</th>
                    <th style="width: 90px;">类型</th>
                    <th>名称</th>
                    <th>说明</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="item in day.items" :key="item.id">
                    <td>{{ item.sortNo }}</td>
                    <td><span class="tag">{{ itemTypeNames[item.itemType] || item.itemType }}</span></td>
                    <td><strong>{{ item.name }}</strong></td>
                    <td>{{ item.description || '—' }}</td>
                  </tr>
                </tbody>
              </table>
              <p v-else class="empty-inline">该天暂未配置行程项目。</p>
            </article>
          </div>

          <div v-else class="empty-box">
            该线路暂未维护每日行程，请联系旅行社运营人员补充后再出团。
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

.trip-actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
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

.day-list {
  display: grid;
  gap: 14px;
}

.day-card {
  border: 1px solid var(--border-divider);
  border-radius: 12px;
  padding: 14px;
  background: var(--bg-subtle, #fafafa);
}

.day-head strong {
  font-size: 14px;
}

.day-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin-top: 4px;
  font-size: 12px;
  color: var(--text-secondary);
}

.day-description {
  margin: 8px 0 0;
  font-size: 13px;
  color: var(--text-secondary);
}

.inner-table {
  margin-top: 12px;
  background: white;
  border-radius: 10px;
}

.empty-inline {
  margin: 10px 0 0;
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
