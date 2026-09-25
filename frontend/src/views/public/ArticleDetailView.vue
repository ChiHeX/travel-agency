<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { contentApi } from '@/api/modules'
import AppIcon from '@/components/AppIcon.vue'
import PanelIconButton from '@/components/PanelIconButton.vue'
import RequestState from '@/components/RequestState.vue'

const route = useRoute()
const router = useRouter()
const article = ref(null)
const places = ref([])
const loading = ref(true)
const error = ref('')
const heroImageFailed = ref(false)

const paragraphs = computed(() => (article.value?.content || '').split(/\r?\n/).map((item) => item.trim()).filter(Boolean))

function formatDate(value) {
  if (!value) return '发布时间待同步'
  return new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: 'short', day: 'numeric' }).format(new Date(value))
}

async function load() {
  loading.value = true
  error.value = ''
  places.value = []
  try {
    article.value = await contentApi.article(route.params.id)
    if (article.value.attractionId) {
      try {
        let page = 1
        let result
        do {
          result = await contentApi.attractions({ page, size: 100 })
          const matched = (result?.items || []).find((item) => String(item.id) === String(article.value.attractionId))
          if (matched) {
            places.value = [matched]
            break
          }
          page += 1
        } while (page <= (result?.totalPages || 1))
      } catch (_) {
        places.value = []
      }
    }
  } catch (cause) {
    error.value = cause.message || '指南详情加载失败'
  } finally {
    loading.value = false
  }
}

async function share() {
  const data = { title: article.value?.title || '旅行指南', url: window.location.href }
  if (navigator.share) await navigator.share(data)
  else await navigator.clipboard?.writeText(data.url)
}

onMounted(load)
</script>

<template>
  <div class="article-detail">
    <RequestState v-if="error" :error="error" @retry="load" />
    <div v-else-if="loading" class="loading-block"><el-skeleton :rows="10" animated /></div>
    <template v-else-if="article">
      <header class="detail-hero" :class="{ 'without-cover': !article.coverUrl || heroImageFailed }">
        <img v-if="article.coverUrl && !heroImageFailed" :src="article.coverUrl" :alt="article.title" @error="heroImageFailed = true" />
        <span class="hero-overlay"></span>
        <PanelIconButton class="hero-action back" action="back" label="返回上一页" :fallback-to="{ name: 'articles', query: article.destination ? { destination: article.destination } : {} }" />
        <PanelIconButton class="hero-action share" action="share" label="分享攻略" @click="share" />
        <div class="hero-content">
          <small>{{ article.authorName }}</small>
          <h1>{{ article.title }}</h1>
          <p v-if="article.summary">{{ article.summary }}</p>
        </div>
      </header>

      <div class="publisher-bar">
        <div class="publisher-link">
          <span class="publisher-avatar">{{ article.authorName.slice(0, 1) }}</span>
          <span><strong>{{ article.authorName }}</strong><small>{{ article.destination || article.city || '旅行攻略' }} · {{ formatDate(article.publishedAt) }}</small></span>
        </div>
        <button type="button" class="source-button" @click="router.push({ name: 'articles', query: article.destination ? { destination: article.destination } : {} })"><AppIcon name="compass" size="17" />更多攻略</button>
      </div>

      <main class="detail-content">
        <section v-if="paragraphs.length" class="story-card">
          <p v-for="(paragraph, index) in paragraphs" :key="index">{{ paragraph }}</p>
        </section>

        <section v-if="article.attractionId" class="places-section">
          <div class="section-heading">
            <div><small>{{ article.city || article.destination || '目的地' }}</small><h2>关联景点</h2></div>
          </div>
          <div v-if="places.length" class="place-list">
            <RouterLink v-for="place in places" :key="place.id" :to="{ name: 'attraction-detail', params: { id: place.id }, query: { city: place.city } }" class="place-card">
              <div class="place-visual"><AppIcon name="pin" size="28" /></div>
              <div class="place-copy">
                <strong>{{ place.name }}</strong>
                <span>{{ place.city }}<template v-if="place.address"> · {{ place.address }}</template></span>
                <p v-if="place.intro">{{ place.intro }}</p>
                <small>查看地点详情</small>
              </div>
              <AppIcon name="chevron-right" size="18" color="#8e8e93" />
            </RouterLink>
          </div>
          <RequestState v-else empty empty-text="关联景点暂不可查看" />
        </section>

        <RouterLink :to="{ name: 'routes', query: { keyword: article.destination || article.city || '' } }" class="route-action">
          <span><strong>探索相关线路</strong><small>查看目的地跟团游</small></span>
          <AppIcon name="chevron-right" size="18" />
        </RouterLink>
      </main>
    </template>
  </div>
</template>

<style scoped>
.article-detail { position: relative; height: 100%; overflow-y: auto; color: #121212; background: #dff4fb; }
.loading-block { padding: 28px 22px; }
.detail-hero { position: relative; min-height: 430px; display: flex; align-items: flex-end; overflow: hidden; color: white; background: #17384a; }
.detail-hero.without-cover { min-height: 330px; background: linear-gradient(150deg,#17384a,#4b8da7 58%,#b8d8cb); }
.detail-hero > img { position: absolute; inset: 0; width: 100%; height: 100%; object-fit: cover; }
.hero-overlay { position: absolute; inset: 0; background: linear-gradient(0deg,rgba(20,8,5,.92),rgba(20,8,5,.05) 70%); }
.hero-action { position: absolute; top: 20px; z-index: 2; }.hero-action.back { left: 20px; }.hero-action.share { right: 20px; }
.hero-content { position: relative; z-index: 1; padding: 28px 24px 24px; }
.hero-content small { font-size: 14px; font-weight: 700; }.hero-content h1 { margin: 12px 0; font-size: 36px; line-height: 1.03; letter-spacing: -.045em; }
.hero-content p { max-height: 6.3em; overflow: hidden; margin: 0; color: rgba(255,255,255,.8); font-size: 16px; line-height: 1.55; }
.publisher-bar { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 16px 22px; color: white; background: #20100c; }
.publisher-link { display: flex; align-items: center; gap: 10px; min-width: 0; }
.publisher-avatar { width: 38px; height: 38px; flex: 0 0 auto; border-radius: 50%; display: grid; place-items: center; color: #17384a; background: #c9eef6; font-weight: 800; }
.publisher-link strong, .publisher-link small { display: block; }.publisher-link strong { font-size: 14px; }.publisher-link small { max-width: 190px; overflow: hidden; color: rgba(255,255,255,.58); font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }
.source-button { min-height: 38px; padding: 0 14px; border: 0; border-radius: 12px; display: inline-flex; align-items: center; gap: 7px; color: white; background: rgba(255,255,255,.12); cursor: pointer; }
.detail-content { display: grid; gap: 24px; padding: 24px; }
.story-card { padding: 20px; border-radius: 18px; background: white; }
.story-card p { margin: 0 0 12px; font-size: 14px; line-height: 1.75; }.story-card p:last-child { margin-bottom: 0; }
.section-heading { display: flex; justify-content: space-between; align-items: flex-end; margin-bottom: 13px; }
.section-heading small { color: #57727d; font-weight: 650; }.section-heading h2 { margin: 2px 0 0; font-size: 23px; letter-spacing: -.03em; }.section-heading > span { color: #57727d; font-size: 12px; }
.place-list { display: grid; gap: 14px; }
.place-card { display: grid; grid-template-columns: 82px 1fr auto; gap: 13px; align-items: center; overflow: hidden; border-radius: 18px; background: white; }
.place-visual { align-self: stretch; min-height: 112px; display: grid; place-items: center; color: #1682ad; background: linear-gradient(150deg,#bfeaf4,#dcebd1); }
.place-copy { padding: 14px 0; min-width: 0; }.place-copy strong, .place-copy span, .place-copy small { display: block; }.place-copy strong { font-size: 17px; }.place-copy span { margin-top: 3px; color: #8e8e93; font-size: 11px; }
.place-copy p { display: -webkit-box; overflow: hidden; margin: 9px 0; color: #414141; font-size: 12px; line-height: 1.45; -webkit-box-orient: vertical; -webkit-line-clamp: 2; }.place-copy small { color: var(--theme-blue); }
.place-card > svg { margin-right: 13px; }
.route-action { display: flex; align-items: center; justify-content: space-between; padding: 18px; border-radius: 18px; color: white; background: var(--theme-blue); }.route-action strong, .route-action small { display: block; }.route-action small { margin-top: 3px; opacity: .75; }
@media (max-width: 900px) { .detail-hero { min-height: 440px; }.hero-content h1 { font-size: 34px; }.detail-content { padding: 20px; }.place-card { grid-template-columns: 74px 1fr auto; } }
</style>
