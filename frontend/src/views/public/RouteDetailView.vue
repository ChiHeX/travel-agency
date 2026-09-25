<script setup>
import { computed, inject, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { accountApi, routeApi } from '@/api/modules'
import { useAuthStore } from '@/stores/auth'
import AppIcon from '@/components/AppIcon.vue'
import DepartureCard from '@/components/DepartureCard.vue'
import PanelIconButton from '@/components/PanelIconButton.vue'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()
const closeDrawer = inject('closeDrawer', () => {})
const setMapItinerary = inject('setMapItinerary', () => {})
const data = ref(null)
const loading = ref(true)
const errorMessage = ref('')
const favorite = ref(false)
const favoriteSubmitting = ref(false)
const selectedDepartureId = ref(null)

const departures = computed(() => data.value?.departures || [])
const selectedDeparture = computed(
  () => departures.value.find((d) => d.id === selectedDepartureId.value) || departures.value[0]
)
const reviews = computed(() => data.value?.reviews || [])

async function load() {
  loading.value = true
  errorMessage.value = ''
  try {
    data.value = await routeApi.detail(route.params.id)
    setMapItinerary(data.value?.itinerary || [])
    favorite.value = Boolean(data.value?.favorite)
    if (departures.value.length > 0) {
      selectedDepartureId.value = departures.value.find(isDepartureBookable)?.id || null
    }
  } catch (error) {
    data.value = null
    setMapItinerary([])
    errorMessage.value = error.message || '线路详情加载失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

async function toggleFavorite() {
  if (!auth.isLoggedIn) return router.push({ name: 'login', query: { redirect: route.fullPath } })
  if (favoriteSubmitting.value) return
  favoriteSubmitting.value = true
  try {
    if (favorite.value) {
      await accountApi.removeFavorite(route.params.id)
      favorite.value = false
      ElMessage.success('已取消收藏')
    } else {
      await accountApi.addFavorite(route.params.id)
      favorite.value = true
      ElMessage.success('已加入收藏')
    }
  } catch (cause) {
    ElMessage.error(cause.message || '收藏操作失败，请重试')
  } finally {
    favoriteSubmitting.value = false
  }
}

function book(departure) {
  if (!auth.isLoggedIn) return router.push({ name: 'login', query: { redirect: route.fullPath } })
  router.push({
    name: 'order-create',
    query: { departureId: departure.id, routeId: data.value.route.id }
  })
}

function getAvailableSeats(item) {
  return item?.availableSeats == null ? null : Number(item.availableSeats)
}

function isDepartureBookable(item) {
  if (!item || (item.status && item.status !== 'OPEN')) return false
  const seats = getAvailableSeats(item)
  return seats != null && seats > 0
}

function selectDeparture(item) {
  if (isDepartureBookable(item)) selectedDepartureId.value = item.id
}

function itineraryType(type) {
  const labels = {
    ATTRACTION: '景点',
    MEAL: '餐食',
    TRANSPORT: '交通',
    ACTIVITY: '活动',
    OTHER: '其他'
  }
  return labels[type] || type || '行程'
}

async function shareRoute() {
  if (navigator.clipboard) {
    try {
      await navigator.clipboard.writeText(window.location.href)
      ElMessage.success('线路链接已复制')
    } catch { ElMessage.warning('复制失败，请复制浏览器地址栏中的链接') }
  }
}

onMounted(load)
onBeforeUnmount(() => setMapItinerary([]))
</script>

<template>
  <div class="place-sheet-drawer">
    <div v-if="loading" class="loading-wrap">
      <el-skeleton :rows="10" animated />
    </div>

    <div v-else-if="errorMessage" class="detail-error-state">
      <AppIcon name="pin" size="24" color="#ff3b30" />
      <strong>线路详情暂时无法加载</strong>
      <p>{{ errorMessage }}</p>
      <button type="button" class="secondary-button" @click="load">重新加载</button>
    </div>

    <template v-else-if="data && data.route">
      <div class="sheet-top-bar">
        <PanelIconButton action="back" @click="router.back()" />
        <div class="sheet-actions">
          <PanelIconButton action="favorite" :active="favorite" :disabled="favoriteSubmitting" @click="toggleFavorite" />
          <PanelIconButton action="share" @click="shareRoute" />
          <PanelIconButton action="close" label="关闭面板" @click="closeDrawer" />
        </div>
      </div>

      <!-- Sheet Scroll Body -->
      <div class="sheet-scroll-body">
        <!-- Hero Media Banner -->
        <div class="sheet-hero-media">
          <img v-if="data.route.coverUrl" :src="data.route.coverUrl" :alt="data.route.name" />
          <div v-else class="hero-fallback"></div>
          <div class="hero-gradient"></div>
          <div class="hero-content">
            <span class="eyebrow-tag">{{ data.route.departureCity }} → {{ data.route.destination }}</span>
            <h3>{{ data.route.name }}</h3>
          </div>
        </div>

        <!-- Waypoints Stop List (Screenshot 3) -->
          <div class="sheet-waypoints-box">
          <div class="waypoint-node">
            <span class="dot blue">
              <AppIcon name="circle" size="13" color="#0071e3" />
            </span>
            <div class="node-info">
              <span class="sub-label">起点 / 出发城市</span>
              <strong>{{ data.route.departureCity }}</strong>
            </div>
            <span class="drag-icon">
              <AppIcon name="drag" size="14" color="#8e8e93" />
            </span>
          </div>

          <div class="connector-line"></div>

          <div class="waypoint-node">
            <span class="dot blue">
              <AppIcon name="pin" size="13" color="#0071e3" />
            </span>
            <div class="node-info">
              <span class="sub-label">终点 / 目的地</span>
              <strong>{{ data.route.destination }}</strong>
            </div>
            <span class="drag-icon">
              <AppIcon name="drag" size="14" color="#8e8e93" />
            </span>
          </div>
          </div>

        <div class="sheet-section route-overview-section">
          <div class="route-overview-head">
            <div>
              <span class="sub-hint">线路简介</span>
              <p class="route-description">{{ data.route.description || '暂无线路简介。' }}</p>
            </div>
            <div class="route-rating-summary">
              <template v-if="data.route.ratingCount">
                <strong>{{ data.route.ratingAvg }}</strong>
                <span>/ 5</span>
                <small>{{ data.route.ratingCount }} 条评价</small>
              </template>
              <span v-else class="muted-rating">暂无评分</span>
            </div>
          </div>
        </div>

        <div class="sheet-section">
          <RouterLink :to="{ name: 'articles', query: { destination: data.route.destination } }" class="article-entry">
            <span><strong>目的地攻略</strong><small>了解{{ data.route.destination }}的景点与旅行建议</small></span>
            <AppIcon name="chevron-right" size="18" />
          </RouterLink>
        </div>

        <!-- Departures Section -->
        <div class="sheet-section">
          <div class="section-title-row">
            <h4>可选出发团期</h4>
            <span class="sub-hint">点击选中</span>
          </div>

          <div v-if="departures.length" class="departures-sheet-list">
            <DepartureCard v-for="item in departures" :key="item.id" :departure="item"
                           :selected="selectedDepartureId === item.id" interactive
                           @select="selectDeparture(item)" />
          </div>
          <div v-else class="empty-box">暂无排期。</div>
        </div>

        <!-- Day by Day Itinerary -->
        <div class="sheet-section">
          <div class="section-title-row">
            <h4>每日行程安排</h4>
            <span class="sub-hint">{{ data.route.durationDays }} 天全程</span>
          </div>

          <div v-if="data.itinerary && data.itinerary.length" class="day-itinerary-list">
            <div v-for="item in data.itinerary" :key="item.id" class="day-row-card">
              <div class="day-badge">D{{ item.dayNumber }}</div>
              <div class="day-content">
                <h5>{{ item.title }}</h5>
                <p>{{ item.description || '暂无当日行程说明。' }}</p>
                <div v-if="item.items && item.items.length" class="itinerary-items-list">
                  <div v-for="entry in item.items" :key="entry.id" class="itinerary-item-row">
                    <span class="itinerary-item-type">{{ itineraryType(entry.itemType) }}</span>
                    <div class="itinerary-item-copy">
                      <strong>{{ entry.name }}</strong>
                      <span v-if="entry.description">{{ entry.description }}</span>
                    </div>
                  </div>
                </div>
                <div class="day-meta-tags">
                  <span class="meta-tag-item">
                    <AppIcon name="bus" size="12" color="#0071e3" />
                    <span>交通：{{ item.transportation || '暂无安排' }}</span>
                  </span>
                  <span>·</span>
                  <span class="meta-tag-item">
                    <AppIcon name="food" size="12" color="#ff9500" />
                    <span>餐食：{{ item.meals || '暂无安排' }}</span>
                  </span>
                  <template v-if="item.hotelName">
                    <span>·</span>
                    <span class="meta-tag-item">
                      <AppIcon name="hotel" size="12" color="#5856d6" />
                      <span>住宿：{{ item.hotelName }}</span>
                    </span>
                  </template>
                </div>
              </div>
            </div>
          </div>
          <div v-else class="empty-box">暂无已发布的每日行程。</div>
        </div>

        <div class="sheet-section route-notes-section">
          <div class="detail-notes-grid">
            <div class="detail-note-card">
              <span class="sub-hint">费用包含</span>
              <p>{{ data.route.included || '暂无说明。' }}</p>
            </div>
            <div class="detail-note-card">
              <span class="sub-hint">费用不包含</span>
              <p>{{ data.route.excluded || '暂无说明。' }}</p>
            </div>
          </div>
          <div class="detail-note-card booking-notice-card">
            <span class="sub-hint">预订须知</span>
            <p>{{ data.route.bookingNotice || '暂无说明。' }}</p>
          </div>
        </div>

        <div class="sheet-section reviews-section">
          <div class="section-title-row">
            <h4>用户评价</h4>
            <span class="sub-hint">{{ data.route.ratingCount || 0 }} 条</span>
          </div>
          <div v-if="reviews.length" class="reviews-list">
            <article v-for="review in reviews" :key="review.id" class="review-row">
              <div class="review-row-head">
                <div class="review-author-rating">
                  <strong>{{ review.userNickname }}</strong>
                  <el-rate :model-value="Number(review.rating) || 0" disabled size="small" />
                </div>
                <span>{{ review.createdAt }}</span>
              </div>
              <p>{{ review.content || '用户未填写文字评价。' }}</p>
            </article>
          </div>
          <div v-else class="empty-box">暂无用户评价。</div>
        </div>
      </div>

      <!-- Bottom Sticky Booking Footer (Frosted Glass) -->
      <div class="sheet-sticky-footer">
        <div class="footer-price-col">
          <span class="label">已选团期成人价</span>
          <div class="price">
            <template v-if="selectedDeparture && selectedDeparture.adultPrice != null">
              <strong>¥{{ selectedDeparture.adultPrice }}</strong>
              <small>起/人</small>
            </template>
            <small v-else>价格待发布</small>
          </div>
        </div>

        <button
          type="button"
          class="primary-button booking-cta-btn"
          :disabled="!isDepartureBookable(selectedDeparture)"
          @click="selectedDeparture && book(selectedDeparture)"
        >
          {{ isDepartureBookable(selectedDeparture) ? '立即报名' : selectedDeparture && getAvailableSeats(selectedDeparture) === 0 ? '团期已满' : '暂无可报名团期' }}
        </button>
      </div>
    </template>
  </div>
</template>

<style scoped>
.place-sheet-drawer {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: transparent;
}

.loading-wrap {
  padding: 24px;
}

.detail-error-state {
  display: grid;
  justify-items: center;
  gap: 8px;
  padding: 80px 24px;
  text-align: center;
}

.detail-error-state strong {
  color: var(--text-primary);
  font-size: 14px;
}

.detail-error-state p {
  max-width: 280px;
  margin: 0 0 8px;
  color: var(--text-secondary);
  font-size: 12px;
  line-height: 1.5;
}

/* Sheet Top Bar (Screenshot 3) */
.sheet-top-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 14px 18px;
  border-bottom: 1px solid rgba(0, 0, 0, 0.08);
}

.sheet-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

/* Sheet Scroll Body */
.sheet-scroll-body {
  flex: 1;
  overflow-y: auto;
  padding: 16px 18px 24px;
  display: flex;
  flex-direction: column;
  gap: 18px;
}

/* Hero Media */
.sheet-hero-media {
  position: relative;
  height: 160px;
  border-radius: var(--radius-md);
  overflow: hidden;
}

.sheet-hero-media img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.hero-fallback {
  width: 100%;
  height: 100%;
  background: var(--theme-blue);
}

.hero-gradient {
  position: absolute;
  inset: 0;
  background: linear-gradient(to top, rgba(0, 0, 0, 0.75) 0%, transparent 60%);
}

.hero-content {
  position: absolute;
  bottom: 12px;
  left: 12px;
  right: 12px;
  color: white;
}

.eyebrow-tag {
  font-size: 10px;
  font-weight: 700;
  opacity: 0.9;
  letter-spacing: 0.05em;
  display: block;
}

.hero-content h3 {
  font-size: 15px;
  font-weight: 700;
  margin: 2px 0 0;
  line-height: 1.3;
}

/* Transport Mode Switcher (Screenshot 3) */
.transport-mode-switch {
  display: flex;
  background: rgba(0, 0, 0, 0.06);
  padding: 3px;
  border-radius: var(--radius-sm);
  gap: 2px;
}

.mode-btn {
  flex: 1;
  border: none;
  background: transparent;
  padding: 6px 0;
  border-radius: 6px;
  font-size: 11px;
  font-weight: 500;
  color: #1d1d1f;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
}

.mode-btn.active {
  background: var(--theme-blue);
  color: #ffffff;
  font-weight: 600;
}

/* Waypoints Box (Screenshot 3) */
.sheet-waypoints-box {
  background: rgba(255, 255, 255, 0.75);
  backdrop-filter: blur(10px);
  -webkit-backdrop-filter: blur(10px);
  border: 1px solid rgba(0, 0, 0, 0.06);
  border-radius: var(--radius-md);
  padding: 12px 14px;
  display: flex;
  flex-direction: column;
}

.waypoint-node {
  display: flex;
  align-items: center;
  gap: 10px;
}

.dot {
  display: flex;
  align-items: center;
}

.node-info {
  flex: 1;
  display: flex;
  flex-direction: column;
}

.sub-label {
  font-size: 9px;
  color: var(--text-tertiary);
  text-transform: uppercase;
}

.node-info strong {
  font-size: 13px;
  color: var(--text-primary);
}

.drag-icon {
  display: flex;
  align-items: center;
}

.connector-line {
  width: 2px;
  height: 16px;
  background: rgba(0, 0, 0, 0.08);
  margin-left: 5px;
  margin-top: 2px;
  margin-bottom: 2px;
}

.route-overview-section {
  padding: 2px 2px 0;
}

.article-entry {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 14px;
  border: 1px solid var(--border-line);
  border-radius: var(--radius-sm);
  background: #fff;
  color: var(--theme-blue);
}

.article-entry strong,
.article-entry small { display: block; }
.article-entry strong { color: var(--text-primary); font-size: 13px; }
.article-entry small { margin-top: 3px; color: var(--text-secondary); font-size: 11px; }

.route-overview-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding: 2px 0;
}

.route-description {
  max-width: 260px;
  margin: 5px 0 0;
  color: var(--text-secondary);
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-line;
}

.route-rating-summary {
  display: grid;
  min-width: 78px;
  justify-items: end;
  color: var(--text-secondary);
  font-size: 11px;
}

.route-rating-summary strong {
  color: #b45309;
  font-size: 20px;
  line-height: 1;
}

.route-rating-summary small {
  color: var(--text-tertiary);
  font-size: 10px;
}

.muted-rating {
  color: var(--text-tertiary);
  font-size: 11px;
}

/* Section Common */
.section-title-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 10px;
}

.section-title-row h4 {
  font-size: 13px;
  font-weight: 700;
  color: var(--text-primary);
  margin: 0;
}

.sub-hint {
  font-size: 11px;
  color: var(--text-tertiary);
}

/* Departures List */
.departures-sheet-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

/* Day Itinerary */
.day-itinerary-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.day-row-card {
  display: grid;
  grid-template-columns: 32px 1fr;
  gap: 10px;
  align-items: flex-start;
  padding: 10px 12px;
  border: 1px solid rgba(0, 0, 0, 0.06);
  border-radius: var(--radius-sm);
  background: rgba(255, 255, 255, 0.75);
  backdrop-filter: blur(10px);
  -webkit-backdrop-filter: blur(10px);
}

.day-badge {
  width: 32px;
  height: 32px;
  border-radius: 6px;
  background: var(--theme-blue-tint);
  color: var(--theme-blue);
  display: grid;
  place-items: center;
  font-size: 12px;
  font-weight: 700;
}

.day-content h5 {
  font-size: 12px;
  font-weight: 600;
  color: var(--text-primary);
  margin: 0 0 4px;
}

.day-content p {
  font-size: 11px;
  color: var(--text-secondary);
  line-height: 1.5;
  margin: 0 0 6px;
}

.itinerary-items-list {
  display: grid;
  gap: 6px;
  margin: 8px 0;
  padding: 8px 0 2px 10px;
  border-left: 2px solid rgba(0, 113, 227, 0.14);
}

.itinerary-item-row {
  display: flex;
  align-items: flex-start;
  gap: 7px;
}

.itinerary-item-type {
  flex: 0 0 auto;
  padding: 2px 5px;
  border-radius: 4px;
  color: var(--theme-blue);
  background: var(--theme-blue-tint);
  font-size: 9px;
  line-height: 1.2;
}

.itinerary-item-copy {
  display: grid;
  gap: 2px;
  min-width: 0;
}

.itinerary-item-copy strong {
  color: var(--text-primary);
  font-size: 11px;
  font-weight: 600;
}

.itinerary-item-copy span {
  color: var(--text-tertiary);
  font-size: 10px;
  line-height: 1.45;
}

.day-meta-tags {
  font-size: 10px;
  color: var(--text-tertiary);
  display: flex;
  align-items: center;
  gap: 6px;
}

.meta-tag-item {
  display: inline-flex;
  align-items: center;
  gap: 3px;
}

.detail-notes-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
}

.detail-note-card {
  padding: 11px 12px;
  border: 1px solid rgba(0, 0, 0, 0.06);
  border-radius: var(--radius-sm);
  background: rgba(255, 255, 255, 0.72);
}

.detail-note-card p {
  margin: 5px 0 0;
  color: var(--text-secondary);
  font-size: 11px;
  line-height: 1.55;
  white-space: pre-line;
}

.booking-notice-card {
  margin-top: 8px;
}

.reviews-list {
  display: grid;
  gap: 8px;
}

.review-row {
  padding: 10px 12px;
  border: 1px solid rgba(0, 0, 0, 0.06);
  border-radius: var(--radius-sm);
  background: rgba(255, 255, 255, 0.72);
}

.review-row-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.review-row-head span {
  color: var(--text-tertiary);
  font-size: 10px;
}

.review-author-rating {
  display: flex;
  align-items: center;
  gap: 8px;
}

.review-author-rating strong {
  color: var(--text-primary);
  font-size: 11px;
}

.review-row p {
  margin: 6px 0 0;
  color: var(--text-secondary);
  font-size: 11px;
  line-height: 1.5;
}

/* Bottom Sticky Footer */
.sheet-sticky-footer {
  padding: 12px 18px;
  border-top: 1px solid rgba(0, 0, 0, 0.08);
  background: rgba(255, 255, 255, 0.9);
  backdrop-filter: blur(20px);
  -webkit-backdrop-filter: blur(20px);
  display: flex;
  justify-content: space-between;
  align-items: center;
  z-index: 10;
}

.footer-price-col .label {
  font-size: 10px;
  color: var(--text-tertiary);
  display: block;
}

.footer-price-col .price {
  display: flex;
  align-items: baseline;
  color: var(--price-color);
}

.footer-price-col .price strong {
  font-size: 18px;
  font-weight: 700;
}

.footer-price-col .price small {
  font-size: 11px;
  color: var(--text-secondary);
  margin-left: 2px;
}

.booking-cta-btn {
  min-width: 110px;
  height: 36px;
}

@media (max-width: 420px) {
  .route-overview-head {
    display: grid;
  }

  .route-rating-summary {
    justify-items: start;
  }

  .detail-notes-grid {
    grid-template-columns: 1fr;
  }
}
</style>
