<script setup>
import { computed, onMounted, ref } from 'vue'
import { contentApi } from '@/api/modules'
import AppIcon from '@/components/AppIcon.vue'
import RequestState from '@/components/RequestState.vue'

const articles = ref([])
const loading = ref(true)
const error = ref('')

const publishers = computed(() => {
  const groups = new Map()
  for (const article of articles.value) {
    const key = String(article.authorId)
    const current = groups.get(key) || { id: key, name: article.authorName, count: 0, destinations: new Set() }
    current.count += 1
    if (article.destination || article.city) current.destinations.add(article.destination || article.city)
    groups.set(key, current)
  }
  return [...groups.values()].map((item) => ({ ...item, destinations: [...item.destinations] }))
})

async function load() {
  loading.value = true
  error.value = ''
  try {
    articles.value = (await contentApi.articles({ page: 1, size: 100 }))?.items || []
  } catch (cause) {
    error.value = cause.message || '发布者加载失败'
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="publisher-directory">
    <header>
      <RouterLink :to="{ name: 'articles' }" class="back-button" aria-label="返回指南"><AppIcon name="chevron-left" size="19" /></RouterLink>
      <div><h1>指南</h1><p>按发布者浏览</p></div>
    </header>
    <main>
      <RequestState v-if="error" :error="error" @retry="load" />
      <div v-else-if="loading"><el-skeleton :rows="9" animated /></div>
      <div v-else-if="publishers.length" class="publisher-list">
        <RouterLink v-for="publisher in publishers" :key="publisher.id" :to="{ name: 'publisher-guides', params: { id: publisher.id }, query: { name: publisher.name } }" class="publisher-row">
          <span class="publisher-mark">{{ publisher.name.slice(0, 1) }}</span>
          <span class="publisher-copy"><strong>{{ publisher.name }}</strong><small>{{ publisher.count }} 个指南<template v-if="publisher.destinations.length"> · {{ publisher.destinations.slice(0, 2).join('、') }}</template></small></span>
          <AppIcon name="chevron-right" size="20" color="#8e8e93" />
        </RouterLink>
      </div>
      <RequestState v-else empty empty-text="暂无指南发布者" />
    </main>
  </div>
</template>

<style scoped>
.publisher-directory { height: 100%; overflow-y: auto; background: #edf9f5; }
header { display: flex; align-items: center; gap: 14px; padding: 24px 22px 17px; border-bottom: 1px solid rgba(0,0,0,.07); background: rgba(255,255,255,.72); }
header h1 { margin: 0; font-size: 29px; letter-spacing: -.04em; } header p { margin: -2px 0 0; color: #9a9a9f; font-size: 17px; font-weight: 650; }
.back-button { width: 38px; height: 38px; flex: 0 0 auto; border-radius: 50%; display: grid; place-items: center; color: #71767a; background: rgba(0,0,0,.055); }
main { padding: 8px 22px 30px; }
.publisher-list { overflow: hidden; border-radius: 17px; background: white; }
.publisher-row { display: grid; grid-template-columns: 45px 1fr auto; gap: 12px; align-items: center; min-height: 78px; margin-left: 16px; padding: 12px 15px 12px 0; border-bottom: 1px solid #dedee2; }
.publisher-row:last-child { border-bottom: 0; }
.publisher-mark { width: 45px; height: 45px; border-radius: 11px; display: grid; place-items: center; color: white; background: linear-gradient(145deg,#1584c7,#0a466e); font-size: 18px; font-weight: 800; }
.publisher-row:nth-child(3n+2) .publisher-mark { background: linear-gradient(145deg,#c28d24,#6d4614); }.publisher-row:nth-child(3n) .publisher-mark { background: linear-gradient(145deg,#ea4e59,#9e0e23); }
.publisher-copy strong, .publisher-copy small { display: block; }.publisher-copy strong { font-size: 17px; }.publisher-copy small { margin-top: 2px; color: #8e8e93; font-size: 13px; }
</style>
