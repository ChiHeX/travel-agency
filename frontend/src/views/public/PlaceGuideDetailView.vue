<script setup>
import { inject, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { placeGuideApi } from '@/api/modules'
import AppIcon from '@/components/AppIcon.vue'
import RequestState from '@/components/RequestState.vue'

const route = useRoute()
const router = useRouter()
const setMapPlaces = inject('setMapPlaces', () => {})
const setMapFocus = inject('setMapFocus', () => {})
const guide = ref(null)
const loading = ref(true)
const error = ref('')
const coverFailed = ref(false)

async function load() {
  loading.value = true
  error.value = ''
  setMapPlaces([])
  setMapFocus(null)
  try {
    guide.value = await placeGuideApi.detail(route.params.id)
    setMapPlaces(guide.value.places || [])
    coverFailed.value = false
  } catch (cause) {
    guide.value = null
    error.value = cause.message || '指南加载失败'
  } finally {
    loading.value = false
  }
}

function openPlace(place) {
  router.push({ name: 'attraction-detail', params: { id: place.attractionId },
    query: { guideId: route.params.id } })
}

async function share() {
  const data = { title: guide.value?.title || '地点指南', url: window.location.href }
  if (navigator.share) {
    try { await navigator.share(data) } catch (_) { /* 用户取消分享 */ }
  } else {
    try { await navigator.clipboard.writeText(data.url) } catch (_) { /* 浏览器可能不支持剪贴板 */ }
  }
}

watch(() => route.params.id, load, { immediate: true })
onBeforeUnmount(() => {
  setMapPlaces([])
  setMapFocus(null)
})
</script>

<template>
  <div class="guide-detail">
    <RequestState v-if="error" :error="error" @retry="load" />
    <div v-else-if="loading" class="loading-block"><el-skeleton :rows="12" animated /></div>
    <template v-else-if="guide">
      <header class="detail-hero" :class="{ 'without-cover': !guide.coverUrl || coverFailed }">
        <img v-if="guide.coverUrl && !coverFailed" :src="guide.coverUrl" :alt="guide.title" @error="coverFailed = true" />
        <span class="hero-shade"></span>
        <div class="hero-actions">
          <button type="button" aria-label="返回指南" @click="router.back()"><AppIcon name="chevron-left" size="20" /></button>
          <button type="button" aria-label="分享指南" @click="share"><AppIcon name="share" size="18" /></button>
        </div>
        <div class="hero-copy">
          <small>{{ guide.authorName }} · {{ guide.city }}</small>
          <h1>{{ guide.title }}</h1>
        </div>
      </header>

      <main>
        <p v-if="guide.summary" class="summary">{{ guide.summary }}</p>
        <div class="places-heading">
          <div><small>{{ guide.city }}</small><h2>{{ guide.places.length }} 个地点</h2></div>
          <span><AppIcon name="pin" size="15" /> 地图已聚焦该区域</span>
        </div>
        <div class="place-list">
          <button v-for="place in guide.places" :key="place.attractionId" type="button"
                  class="place-row" @click="openPlace(place)">
            <span class="place-number">{{ place.sortOrder }}</span>
            <span class="place-copy">
              <strong>{{ place.name }}</strong>
              <small>{{ place.address || place.city }}</small>
              <span v-if="place.note">{{ place.note }}</span>
            </span>
            <AppIcon name="chevron-right" size="17" />
          </button>
        </div>
        <RouterLink :to="{ name: 'city-guides', params: { city: guide.city } }" class="more-guides">
          查看{{ guide.city }}的更多指南 <AppIcon name="chevron-right" size="16" />
        </RouterLink>
      </main>
    </template>
  </div>
</template>

<style scoped>
.guide-detail { height: 100%; overflow-y: auto; color: #1d1d1f; background: #f7faf9; }
.loading-block { padding: 25px; }
.detail-hero { position: relative; min-height: 290px; overflow: hidden; color: white; background: linear-gradient(135deg, #76b6c6, #518273); }
.detail-hero img, .hero-shade { position: absolute; inset: 0; width: 100%; height: 100%; }
.detail-hero img { object-fit: cover; }.hero-shade { background: linear-gradient(0deg, rgba(0,0,0,.75), transparent 75%); }
.hero-actions { position: relative; display: flex; justify-content: space-between; padding: 20px; }
.hero-actions button { display: grid; place-items: center; width: 38px; height: 38px; border: 0; border-radius: 50%; color: #1d1d1f; background: rgba(255,255,255,.9); cursor: pointer; }
.hero-copy { position: relative; padding: 125px 22px 22px; }.hero-copy small { font-size: 12px; font-weight: 650; }.hero-copy h1 { margin: 5px 0 0; font-size: 26px; line-height: 1.15; }
main { padding: 22px; }.summary { margin: 0 0 24px; color: #545e5a; font-size: 14px; line-height: 1.7; }
.places-heading { display: flex; justify-content: space-between; align-items: end; gap: 8px; margin-bottom: 12px; }
.places-heading small { color: #7b8580; font-size: 11px; }.places-heading h2 { margin: 3px 0 0; font-size: 20px; }
.places-heading > span { display: inline-flex; align-items: center; gap: 3px; color: #67837b; font-size: 11px; }
.place-list { overflow: hidden; border-radius: 17px; background: white; }
.place-row { display: flex; align-items: center; gap: 12px; width: 100%; min-height: 78px; padding: 12px 16px; border: 0; border-bottom: 1px solid #ecf0ee; color: #79837e; background: white; text-align: left; cursor: pointer; }
.place-row:last-child { border-bottom: 0; }.place-row:hover, .place-row.selected { background: #edf6f2; }
.place-number { display: grid; place-items: center; flex: 0 0 auto; width: 30px; height: 30px; border-radius: 50%; color: white; background: #0071e3; font-size: 12px; font-weight: 700; }
.place-copy { display: grid; flex: 1; gap: 3px; min-width: 0; }.place-copy strong { color: #1d1d1f; font-size: 14px; }.place-copy small, .place-copy > span { color: #7b8580; font-size: 11px; line-height: 1.4; }
.more-guides { display: inline-flex; align-items: center; gap: 4px; margin-top: 22px; color: #0071e3; font-size: 13px; text-decoration: none; }
@media (max-width: 900px) { main { padding-bottom: calc(35px + env(safe-area-inset-bottom)); } }
</style>
