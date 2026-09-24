<script setup>
import { ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { placeGuideApi } from '@/api/modules'
import AppIcon from '@/components/AppIcon.vue'
import RequestState from '@/components/RequestState.vue'

const router = useRouter()
const route = useRoute()
const articles = ref([])
const loading = ref(true)
const error = ref('')
const page = ref(1)
const size = 20
const total = ref(0)
const failedImages = ref(new Set())

async function load() {
  loading.value = true
  error.value = ''
  try {
    const result = await placeGuideApi.list({
      page: page.value, size,
      ...(route.query.city ? { city: String(route.query.city) } : {})
    })
    articles.value = result?.items || []
    total.value = result?.total || 0
  } catch (cause) {
    error.value = cause.message || '最新指南加载失败'
  } finally {
    loading.value = false
  }
}

function markImageFailed(id) {
  failedImages.value = new Set(failedImages.value).add(String(id))
}

function changePage(value) {
  page.value = value
  load()
}

watch(() => route.query.city, () => { page.value = 1; load() }, { immediate: true })
</script>

<template>
  <div class="latest-page">
    <header>
      <button type="button" class="back-button" aria-label="返回指南" @click="router.back()"><AppIcon name="chevron-left" size="21" /></button>
      <h1>最新</h1>
    </header>

    <main>
      <RequestState v-if="error" :error="error" @retry="load" />
      <div v-else-if="loading" class="loading-block"><el-skeleton :rows="12" animated /></div>
      <template v-else-if="articles.length">
        <div class="latest-list">
          <RouterLink v-for="article in articles" :key="article.id" :to="{ name: 'guide-detail', params: { id: article.id } }" class="latest-card">
            <div class="media-fallback"><AppIcon name="guides" size="34" /></div>
            <img v-if="article.coverUrl && !failedImages.has(String(article.id))" :src="article.coverUrl" :alt="article.title" @error="markImageFailed(article.id)" />
            <i></i>
            <span><small>{{ article.authorName }}</small><strong>{{ article.title }}</strong></span>
          </RouterLink>
        </div>
        <el-pagination v-if="total > size" background layout="prev, pager, next" :page-size="size" :total="total" :current-page="page" @current-change="changePage" />
      </template>
      <RequestState v-else empty empty-text="暂无已发布指南" />
    </main>
  </div>
</template>

<style scoped>
.latest-page { height: 100%; overflow-y: auto; color: #111; background: #f7faf9; }
header { position: sticky; z-index: 4; top: 0; display: flex; align-items: center; gap: 14px; padding: 20px 22px 14px; background: rgba(247,250,249,.92); backdrop-filter: blur(18px); }
.back-button { width: 42px; height: 42px; flex: 0 0 auto; border: 0; border-radius: 50%; display: grid; place-items: center; color: #6f777b; background: rgba(0,0,0,.055); cursor: pointer; }
header h1 { margin: 0; font-size: 29px; letter-spacing: -.04em; }
main { padding: 10px 22px 34px; }.loading-block { padding-top: 8px; }
.latest-list { display: grid; gap: 14px; }
.latest-card { position: relative; height: 215px; overflow: hidden; border-radius: 18px; color: white; background: #bfe5ed; }
.latest-card img, .media-fallback { position: absolute; inset: 0; width: 100%; height: 100%; object-fit: cover; }.media-fallback { display: grid; place-items: center; color: #217b9e; background: linear-gradient(145deg,#bde7f1,#dce9d0); }
.latest-card > i { position: absolute; inset: 0; background: linear-gradient(0deg,rgba(0,0,0,.72),transparent 64%); }
.latest-card > span { position: absolute; right: 20px; bottom: 18px; left: 20px; display: grid; gap: 4px; }.latest-card small { font-size: 12px; font-weight: 650; }.latest-card strong { font-size: 20px; line-height: 1.16; letter-spacing: -.02em; }
.el-pagination { justify-content: center; margin-top: 22px; }
@media (max-width: 900px) { header { padding-top: 14px; }.latest-card { height: 235px; } }
</style>
