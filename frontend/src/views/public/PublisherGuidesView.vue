<script setup>
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { placeGuideApi } from '@/api/modules'
import AppIcon from '@/components/AppIcon.vue'
import RequestState from '@/components/RequestState.vue'

const route = useRoute()
const articles = ref([])
const loading = ref(true)
const error = ref('')
const destination = ref('')
const failedImages = ref(new Set())
const publisherName = computed(() => articles.value[0]?.authorName || '指南发布者')
const destinations = computed(() => [...new Set(articles.value.map((item) => item.destination || item.city).filter(Boolean))])
const visibleArticles = computed(() => destination.value ? articles.value.filter((item) => [item.destination, item.city].includes(destination.value)) : articles.value)

async function load() {
  loading.value = true
  error.value = ''
  try {
    const first = await placeGuideApi.list({ page: 1, size: 100, authorId: route.params.id })
    const all = [...(first?.items || [])]
    for (let page = 2; page <= (first?.totalPages || 1); page += 1) {
      const result = await placeGuideApi.list({ page, size: 100, authorId: route.params.id })
      all.push(...(result?.items || []))
    }
    articles.value = all
  } catch (cause) {
    error.value = cause.message || '发布者指南加载失败'
  } finally {
    loading.value = false
  }
}

function markImageFailed(id) {
  failedImages.value = new Set(failedImages.value).add(String(id))
}

async function share() {
  const data = { title: `${publisherName.value}的旅行指南`, url: window.location.href }
  if (navigator.share) await navigator.share(data)
  else await navigator.clipboard?.writeText(data.url)
}

watch(() => route.params.id, load, { immediate: true })
</script>

<template>
  <div class="publisher-page">
    <header>
      <div class="top-actions">
        <RouterLink :to="{ name: 'guide-publishers' }" class="circle-button" aria-label="返回发布者列表"><AppIcon name="chevron-left" size="19" /></RouterLink>
        <button type="button" class="circle-button" aria-label="分享" @click="share"><AppIcon name="share" size="18" /></button>
      </div>
      <div class="publisher-brand"><span>{{ publisherName.slice(0, 1) }}</span><h1>{{ publisherName }}</h1></div>
    </header>
    <nav v-if="destinations.length" aria-label="发布者目的地筛选">
      <button type="button" :class="{ active: !destination }" @click="destination = ''">所有指南</button>
      <button v-for="item in destinations" :key="item" type="button" :class="{ active: destination === item }" @click="destination = item">{{ item }}</button>
    </nav>
    <main>
      <RequestState v-if="error" :error="error" @retry="load" />
      <div v-else-if="loading"><el-skeleton :rows="9" animated /></div>
      <div v-else-if="visibleArticles.length" class="publisher-guides">
        <RouterLink v-for="article in visibleArticles" :key="article.id" :to="{ name: 'guide-detail', params: { id: article.id } }" class="publisher-guide-card">
          <div class="guide-fallback"><AppIcon name="guides" size="32" /></div>
          <img v-if="article.coverUrl && !failedImages.has(String(article.id))" :src="article.coverUrl" :alt="article.title" @error="markImageFailed(article.id)" />
          <span></span><strong>{{ article.title }}</strong>
        </RouterLink>
      </div>
      <RequestState v-else empty empty-text="该发布者暂无符合条件的指南" />
    </main>
  </div>
</template>

<style scoped>
.publisher-page { height: 100%; overflow-y: auto; background: #dff6fb; }
header { padding: 20px 22px 28px; background: linear-gradient(135deg,#edf8f5,#e7f5db); }
.top-actions { display: flex; justify-content: space-between; }.circle-button { width: 38px; height: 38px; border: 0; border-radius: 50%; display: grid; place-items: center; color: #778084; background: rgba(0,0,0,.055); }
.publisher-brand { display: flex; align-items: center; justify-content: center; gap: 10px; margin-top: 11px; }.publisher-brand > span { width: 40px; height: 40px; border: 2px solid #9b7b38; border-radius: 50%; display: grid; place-items: center; color: #9b7b38; font-weight: 800; }.publisher-brand h1 { margin: 0; font-size: 27px; letter-spacing: -.04em; }
nav { display: flex; gap: 8px; overflow-x: auto; padding: 16px 22px; background: linear-gradient(135deg,#edf8f5,#e7f5db); scrollbar-width: none; } nav::-webkit-scrollbar { display: none; }
nav button { flex: 0 0 auto; padding: 8px 14px; border: 1px solid rgba(0,0,0,.1); border-radius: 999px; background: transparent; cursor: pointer; } nav button.active { color: white; border-color: var(--theme-blue); background: var(--theme-blue); }
main { padding: 0 22px 30px; }.publisher-guides { display: grid; gap: 13px; }
.publisher-guide-card { position: relative; height: 220px; overflow: hidden; border-radius: 18px; color: white; background: #bfe6ed; }
.publisher-guide-card img, .guide-fallback { width: 100%; height: 100%; object-fit: cover; }.publisher-guide-card img { position: absolute; inset: 0; }.guide-fallback { display: grid; place-items: center; color: #1b789a; }
.publisher-guide-card > span { position: absolute; inset: 0; background: linear-gradient(0deg,rgba(0,0,0,.7),transparent 55%); }.publisher-guide-card strong { position: absolute; right: 18px; bottom: 16px; left: 18px; font-size: 20px; line-height: 1.15; }
</style>
