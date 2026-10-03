<script setup>
import { computed, inject, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { routeApi } from '@/api/modules'
import AppIcon from '@/components/AppIcon.vue'
import StickyDetailBar from '@/components/StickyDetailBar.vue'
import RequestState from '@/components/RequestState.vue'
import { checkInLabel, checkOutLabel, facilityLabel, starRatingLabel } from '@/utils/hotel'

const route = useRoute()
const setMapFocus = inject('setMapFocus', () => {})
const loading = ref(false)
const error = ref('')
const hotel = ref(null)

const hotelName = computed(() => hotel.value?.name || '酒店详情')
const backTo = computed(() => ({ name: 'route-detail', params: { id: route.params.routeId } }))

const gallery = computed(() => (hotel.value?.images || []).filter((image) => image?.url))

const coverUrl = computed(() => hotel.value?.coverUrl || gallery.value[0]?.url || '')

const facilities = computed(() => (hotel.value?.facilities || [])
  .map((value) => ({ value, label: facilityLabel(value) }))
  .filter((item) => item.label !== null))
const starLabel = computed(() => starRatingLabel(hotel.value?.starRating))
const checkIn = computed(() => checkInLabel(hotel.value?.checkInTime))
const checkOut = computed(() => checkOutLabel(hotel.value?.checkOutTime))

let requestId = 0
async function load() {
  const current = ++requestId
  loading.value = true
  error.value = ''
  hotel.value = null
  setMapFocus(null)
  try {
    const result = await routeApi.hotel(route.params.routeId, route.params.hotelId)
    if (current !== requestId) return
    hotel.value = result || null
    if (!hotel.value) error.value = '该酒店资料暂不可查看'
    const { longitude, latitude, name } = hotel.value || {}
    if (typeof longitude === 'number' && typeof latitude === 'number' &&
      Number.isFinite(longitude) && Number.isFinite(latitude) &&
      Math.abs(longitude) <= 180 && Math.abs(latitude) <= 90) {
      setMapFocus({ name, longitude, latitude })
    }
  } catch {
    if (current === requestId) error.value = '该酒店资料暂不可查看'
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
onBeforeUnmount(() => {
  requestId++
  setMapFocus(null)
})
</script>

<template>
  <div class="hotel-detail">
    <StickyDetailBar :title="hotelName" :fallback-to="backTo" @share="share" />
    <main>
      <RequestState :loading="loading" :error="error" @retry="load">
        <template v-if="hotel">
          <header class="hotel-header">
            <div class="hotel-kicker"><AppIcon name="hotel" :size="14" />{{ hotel.city }} · 行程住宿</div>
            <h1>{{ hotel.name }}</h1>
            <p v-if="hotel.address">{{ hotel.address }}</p>
          </header>

          <img v-if="coverUrl" class="hotel-cover-media" :src="coverUrl" :alt="hotel.name" />
          <div v-else class="hotel-cover"><AppIcon name="hotel" :size="48" /><span>酒店图片暂未提供</span></div>

          <section>
            <h2>酒店资料</h2>
            <div class="hotel-info-card">
              <div class="hotel-info-row"><span>城市</span><strong>{{ hotel.city }}</strong></div>
              <div v-if="hotel.address" class="hotel-info-row"><span>地址</span><strong>{{ hotel.address }}</strong></div>

              <div v-if="starLabel" class="hotel-info-row"><span>星级</span><strong>{{ starLabel }}</strong></div>
              <div v-if="checkIn" class="hotel-info-row"><span>入住</span><strong>{{ checkIn }}</strong></div>
              <div v-if="checkOut" class="hotel-info-row"><span>退房</span><strong>{{ checkOut }}</strong></div>
              <div v-if="hotel.dataSource" class="hotel-info-row"><span>资料来源</span><strong>{{ hotel.dataSource }}</strong></div>
            </div>
          </section>

          <section>
            <h2>关于酒店</h2>
            <div class="hotel-info-card">
              <p v-if="hotel.intro" class="hotel-intro">{{ hotel.intro }}</p>
              <p v-else class="hotel-empty">酒店暂未提供公开简介。</p>
            </div>
          </section>

          <section>
            <h2>酒店设施</h2>
            <div class="hotel-info-card">
              <div v-if="facilities.length" class="hotel-facilities">
                <span v-for="facility in facilities" :key="facility.value" class="hotel-facility">{{ facility.label }}</span>
              </div>
              <p v-else class="hotel-empty">酒店暂未提供设施资料。</p>
            </div>
          </section>

          <section v-if="gallery.length">
            <h2>酒店图片</h2>
            <div class="hotel-gallery">
              <img
                v-for="image in gallery"
                :key="image.url"
                :src="image.url"
                :alt="image.alt || hotel.name"
                loading="lazy"
              />
            </div>
          </section>

          <RouterLink :to="backTo" class="secondary-button hotel-back">返回线路详情</RouterLink>
        </template>
      </RequestState>

      <p v-if="!loading && error" class="hotel-error-hint">
        这条线路的行程里可能没有这家酒店，或该酒店已停止对外展示。你可以返回线路详情查看最新的每日行程安排。
      </p>
      <RouterLink v-if="!loading && !hotel" :to="backTo" class="secondary-button hotel-back">返回线路详情</RouterLink>
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
.hotel-cover-media { display: block; width: 100%; min-height: 180px; max-height: 280px; border-radius: var(--radius-lg); background: #edf0f5; object-fit: cover; }
.hotel-detail section { margin-top: 28px; }
.hotel-detail h2 { font-size: 15px; font-weight: 600; margin: 0 0 12px; }
.hotel-info-card { padding: 18px; border: 1px solid var(--border-divider); border-radius: var(--radius-md); background: #fff; }
.hotel-info-row { display: grid; grid-template-columns: 64px minmax(0, 1fr); gap: 12px; font-size: 12px; line-height: 1.7; }
.hotel-info-row + .hotel-info-row { margin-top: 12px; }
.hotel-info-row span, .hotel-empty { color: var(--text-secondary); }
.hotel-info-row strong { font-weight: 500; overflow-wrap: anywhere; }
.hotel-empty { margin: 0; font-size: 12px; line-height: 1.8; }
.hotel-intro { margin: 0; font-size: 12px; line-height: 1.8; color: var(--text-secondary); white-space: pre-wrap; }
.hotel-facilities { display: flex; flex-wrap: wrap; gap: 8px; }
.hotel-facility { padding: 5px 10px; border-radius: 999px; background: var(--theme-blue-tint); color: var(--theme-blue); font-size: 11px; }
.hotel-gallery { display: grid; grid-template-columns: repeat(auto-fill, minmax(140px, 1fr)); gap: 10px; }
.hotel-gallery img { width: 100%; height: 110px; border-radius: var(--radius-sm); background: #edf0f5; object-fit: cover; }
.hotel-error-hint { margin: 12px 0 0; color: var(--text-secondary); font-size: 12px; line-height: 1.8; }
.hotel-back { display: inline-flex; align-items: center; justify-content: center; margin-top: 28px; text-align: center; }
</style>
