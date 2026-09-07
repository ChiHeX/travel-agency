<script setup>
import { computed, inject, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { contentApi } from '@/api/modules'
import AppIcon from '@/components/AppIcon.vue'

const router = useRouter()
const currentRoute = useRoute()
const closeDrawer = inject('closeDrawer', () => {})
const articles = ref([])
const loading = ref(false)
const cityFilter = ref(currentRoute.query.city || '')

const cities = computed(() => [...new Set(articles.value.map((item) => item.city || item.destination).filter(Boolean))])
const filteredArticles = computed(() => cityFilter.value
  ? articles.value.filter((item) => (item.city || item.destination) === cityFilter.value)
  : articles.value)
const featuredArticle = computed(() => filteredArticles.value[0])
const remainingArticles = computed(() => filteredArticles.value.slice(1))

async function load() {
  loading.value = true
  try {
    articles.value = (await contentApi.articles())?.items || []
  } finally {
    loading.value = false
  }
}

function filterByCity(city) {
  cityFilter.value = city
  router.replace({ query: city ? { city } : {} })
}

function openArticle(id) {
  router.push({ name: 'article-detail', params: { id } })
}

watch(() => currentRoute.query.city, (value) => { cityFilter.value = value || '' })
onMounted(load)
</script>

<template>
  <div class="guides-drawer-panel">
    <div class="drawer-header-bar">
      <div><h2>指南</h2><span class="drawer-subtitle">来自旅行社的目的地实用信息</span></div>
      <button type="button" class="drawer-close-btn" title="关闭面板" @click="closeDrawer"><AppIcon name="close" size="13" /></button>
    </div>

    <div class="drawer-scroll-body">
      <div v-if="cities.length" class="city-filter-row">
        <button type="button" :class="{ active: !cityFilter }" @click="filterByCity('')">全部</button>
        <button v-for="city in cities" :key="city" type="button" :class="{ active: cityFilter === city }" @click="filterByCity(city)">{{ city }}</button>
      </div>

      <div v-if="loading" class="guide-loading"><el-skeleton :rows="8" animated /></div>
      <template v-else-if="filteredArticles.length">
        <RouterLink v-if="featuredArticle" class="featured-guide" :to="{ name: 'article-detail', params: { id: featuredArticle.id } }">
          <img v-if="featuredArticle.coverUrl" :src="featuredArticle.coverUrl" :alt="featuredArticle.title" loading="lazy" />
          <div v-else class="guide-cover-fallback"><AppIcon name="guides" size="28" color="#0071e3" /></div>
          <div class="featured-guide-copy"><span>{{ featuredArticle.city || featuredArticle.destination || '目的地指南' }}</span><strong>{{ featuredArticle.title }}</strong><small>阅读指南 →</small></div>
        </RouterLink>

        <div class="guide-list">
          <button v-for="article in remainingArticles" :key="article.id" type="button" class="guide-list-row" @click="openArticle(article.id)">
            <div class="guide-list-cover"><img v-if="article.coverUrl" :src="article.coverUrl" :alt="article.title" loading="lazy" /><AppIcon v-else name="guides" size="19" color="#0071e3" /></div>
            <div class="guide-list-copy"><span>{{ article.city || article.destination || '目的地指南' }}</span><strong>{{ article.title }}</strong><small v-if="article.publishedAt">{{ article.publishedAt }}</small></div>
            <AppIcon name="chevron-right" size="14" color="#8e8e93" />
          </button>
        </div>
      </template>
      <div v-else class="empty-results"><AppIcon name="guides" size="24" color="#8e8e93" /><strong>{{ cityFilter ? '该目的地暂无指南' : '暂无已发布指南' }}</strong><p>{{ cityFilter ? '请选择其他目的地，或稍后再来查看。' : '旅行社发布指南后会在此显示。' }}</p><button v-if="cityFilter" type="button" class="secondary-button" @click="filterByCity('')">查看全部</button></div>
    </div>
  </div>
</template>

<style scoped>
.guides-drawer-panel { display: flex; flex-direction: column; height: 100%; background: transparent; }.drawer-header-bar { display: flex; justify-content: space-between; align-items: center; padding: 16px 20px 13px; border-bottom: 1px solid rgba(0,0,0,.08); }.drawer-header-bar h2 { margin: 0; color: #1d1d1f; font-size: 20px; font-weight: 700; }.drawer-subtitle { display: block; margin-top: 4px; color: #8e8e93; font-size: 11px; }.drawer-close-btn { width: 30px; height: 30px; border: 0; border-radius: 50%; color: #86868b; background: rgba(0,0,0,.05); display: grid; place-items: center; cursor: pointer; }.drawer-scroll-body { flex: 1; overflow-y: auto; padding: 15px 18px 28px; }.city-filter-row { display: flex; gap: 7px; overflow-x: auto; padding-bottom: 15px; scrollbar-width: none; }.city-filter-row::-webkit-scrollbar { display: none; }.city-filter-row button { flex: 0 0 auto; padding: 6px 12px; border: 1px solid rgba(0,0,0,.08); border-radius: 18px; color: #4b5563; background: white; font-size: 11px; cursor: pointer; }.city-filter-row button.active { color: white; border-color: var(--theme-blue); background: var(--theme-blue); }.featured-guide { position: relative; display: block; height: 205px; overflow: hidden; border-radius: 13px; background: #f0f4f7; }.featured-guide img, .guide-cover-fallback { width: 100%; height: 100%; object-fit: cover; }.guide-cover-fallback { display: grid; place-items: center; background: #eaf2fb; }.featured-guide::after { content: ''; position: absolute; inset: 0; background: linear-gradient(0deg, rgba(0,0,0,.72), transparent 66%); }.featured-guide-copy { position: absolute; z-index: 1; right: 16px; bottom: 15px; left: 16px; color: white; }.featured-guide-copy span, .featured-guide-copy small { display: block; font-size: 10px; opacity: .86; }.featured-guide-copy strong { display: block; margin: 5px 0; font-size: 17px; line-height: 1.3; }.guide-list { display: grid; gap: 2px; margin-top: 14px; }.guide-list-row { display: grid; grid-template-columns: 74px minmax(0,1fr) 14px; gap: 11px; align-items: center; width: 100%; padding: 8px; border: 0; border-radius: 10px; background: transparent; text-align: left; cursor: pointer; }.guide-list-row:hover { background: rgba(0,0,0,.04); }.guide-list-cover { width: 74px; height: 60px; overflow: hidden; border-radius: 8px; display: grid; place-items: center; background: #eaf2fb; }.guide-list-cover img { width: 100%; height: 100%; object-fit: cover; }.guide-list-copy { display: grid; gap: 4px; min-width: 0; }.guide-list-copy span, .guide-list-copy small { color: #8e8e93; font-size: 10px; }.guide-list-copy strong { overflow: hidden; color: #1d1d1f; font-size: 13px; line-height: 1.35; text-overflow: ellipsis; white-space: nowrap; }.empty-results { display: grid; justify-items: center; gap: 8px; padding: 70px 15px; color: #8e8e93; text-align: center; }.empty-results strong { color: #4b5563; font-size: 14px; }.empty-results p { margin: 0; font-size: 12px; }.empty-results .secondary-button { margin-top: 7px; }.guide-loading { padding: 12px 4px; }
</style>
