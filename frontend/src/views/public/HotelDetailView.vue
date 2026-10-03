<script setup>
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { routeApi } from '@/api/modules'
import AppIcon from '@/components/AppIcon.vue'
import StickyDetailBar from '@/components/StickyDetailBar.vue'
import RequestState from '@/components/RequestState.vue'

const route = useRoute()
const loading = ref(false)
const error = ref('')
const routeDetail = ref(null)
const days = ref([])
const hotelName = computed(() => days.value[0]?.hotelName || '行程酒店')
const backTo = computed(() => ({ name: 'route-detail', params: { id: route.params.routeId } }))
let requestId = 0
async function load() {
  const current = ++requestId
  loading.value = true
  error.value = ''
  routeDetail.value = null
  days.value = []
  try {
    const result = await routeApi.detail(route.params.routeId)
    if (current !== requestId) return
    const matching = (result.itinerary || []).filter((day) => String(day.hotelId) === route.params.hotelId)
    if (!matching.length) throw new Error('该线路未安排这家酒店，请返回线路查看最新行程。')
    routeDetail.value = result.route
    days.value = matching
  } catch (cause) {
    if (current === requestId) error.value = cause.message || '酒店信息加载失败'
  } finally {
    if (current === requestId) loading.value = false
  }
}
async function share() {
  try {
    if (navigator.share) await navigator.share({ title: hotelName.value, url: window.location.href })
    else {
      await navigator.clipboard.writeText(window.location.href)
      ElMessage.success('酒店链接已复制')
    }
  } catch (cause) { if (cause.name !== 'AbortError') ElMessage.warning('分享失败，请复制浏览器地址栏中的链接') }
}
watch(() => [route.params.routeId, route.params.hotelId], load, { immediate: true })
</script>

<template>
  <div class="hotel-detail">
    <StickyDetailBar :title="hotelName" :fallback-to="backTo" @share="share" />
    <main>
      <RequestState :loading="loading" :error="error" @retry="load">
        <template v-if="routeDetail">
          <header class="hotel-header">
            <div class="hotel-kicker"><AppIcon name="hotel" :size="14" />行程住宿</div>
            <h1>{{ hotelName }}</h1>
            <p>{{ routeDetail.name }}</p>
          </header>
          <div class="hotel-cover"><AppIcon name="hotel" :size="48" /><span>酒店图片暂未提供</span></div>
          <section>
            <h2>本次住宿安排</h2>
            <div class="hotel-info-card">
              <div class="hotel-info-row"><span>入住行程</span><strong>{{ days.map((day) => `第 ${day.dayNumber} 天`).join('、') }}</strong></div>
              <div class="hotel-info-row"><span>安排酒店</span><strong>{{ hotelName }}</strong></div>
            </div>
          </section>
          <section>
            <h2>关于酒店</h2>
            <div class="hotel-info-card"><p class="hotel-empty">酒店简介、地址和设施资料暂未提供。</p></div>
          </section>
          <section>
            <h2>对应行程</h2>
            <RouterLink v-for="day in days" :key="day.id" :to="backTo" class="hotel-day-link">
              <span class="hotel-day-number">D{{ day.dayNumber }}</span>
              <div><strong>{{ day.title }}</strong><p>{{ day.description || '返回线路查看完整行程' }}</p></div>
              <AppIcon name="chevron-right" :size="16" />
            </RouterLink>
          </section>
          <RouterLink :to="backTo" class="secondary-button hotel-back">返回线路详情</RouterLink>
        </template>
      </RequestState>
    </main>
  </div>
</template>

<style scoped>
.hotel-detail { color: var(--text-primary); }
.hotel-detail main { padding: 12px 20px 32px; }
.hotel-kicker { display: flex; align-items: center; gap: 6px; color: var(--theme-blue); font-size: 11px; }
.hotel-header h1 { margin: 12px 0 8px; font-size: 26px; font-weight: 600; line-height: 1.3; letter-spacing: -.5px; overflow-wrap: anywhere; }
.hotel-header > p { margin: 0 0 24px; color: var(--text-secondary); font-size: 12px; line-height: 1.6; }
.hotel-cover { display: grid; place-content: center; justify-items: center; gap: 12px; min-height: 180px; border-radius: var(--radius-lg); background: #edf0f5; color: #8c99ac; }
.hotel-cover span { font-size: 11px; }
.hotel-detail section { margin-top: 28px; }
.hotel-detail h2 { font-size: 15px; font-weight: 600; margin: 0 0 12px; }
.hotel-info-card { padding: 18px; border: 1px solid var(--border-divider); border-radius: var(--radius-md); background: #fff; }
.hotel-info-row { display: grid; grid-template-columns: 64px minmax(0, 1fr); gap: 12px; font-size: 12px; line-height: 1.7; }
.hotel-info-row + .hotel-info-row { margin-top: 12px; }
.hotel-info-row span, .hotel-empty { color: var(--text-secondary); }
.hotel-info-row strong { font-weight: 500; overflow-wrap: anywhere; }
.hotel-empty { margin: 0; font-size: 12px; line-height: 1.8; }
.hotel-day-link { display: flex; align-items: center; gap: 12px; padding: 16px 0; border-bottom: 1px solid var(--border-divider); color: inherit; }
.hotel-day-number { display: grid; place-items: center; width: 36px; height: 36px; flex-shrink: 0; border-radius: 10px; color: var(--theme-blue); background: var(--theme-blue-tint); font-size: 12px; }
.hotel-day-link > div { flex: 1; min-width: 0; }
.hotel-day-link strong { font-size: 12px; font-weight: 500; }
.hotel-day-link p { margin: 5px 0 0; font-size: 11px; color: var(--text-tertiary); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.hotel-day-link > svg { flex-shrink: 0; color: var(--text-tertiary); }
.hotel-back { margin-top: 28px; }
</style>
