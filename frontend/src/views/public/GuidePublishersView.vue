<script setup>
import { onMounted, ref } from 'vue'
import { placeGuideApi } from '@/api/modules'
import AppIcon from '@/components/AppIcon.vue'
import PanelIconButton from '@/components/PanelIconButton.vue'
import RequestState from '@/components/RequestState.vue'

const publishers = ref([])
const loading = ref(true)
const error = ref('')

async function load() {
  loading.value = true
  error.value = ''
  try {
    publishers.value = await placeGuideApi.publishers()
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
      <PanelIconButton action="back" label="返回上一页" :fallback-to="{ name: 'guides' }" />
      <div><h1>指南</h1><p>按发布者浏览</p></div>
    </header>
    <main>
      <RequestState v-if="error" :error="error" @retry="load" />
      <div v-else-if="loading"><el-skeleton :rows="9" animated /></div>
      <div v-else-if="publishers.length" class="publisher-list">
        <RouterLink v-for="publisher in publishers" :key="publisher.id" :to="{ name: 'publisher-guides', params: { id: publisher.id }, query: { name: publisher.name } }" class="publisher-row">
          <span class="publisher-mark">{{ publisher.name.slice(0, 1) }}</span>
          <span class="publisher-copy"><strong>{{ publisher.name }}</strong><small>{{ publisher.count }} 个指南</small></span>
          <AppIcon name="chevron-right" size="20" color="#8e8e93" />
        </RouterLink>
      </div>
      <RequestState v-else empty empty-text="暂无指南发布者" />
    </main>
  </div>
</template>

<style scoped>
.publisher-directory { height: 100%; overflow-y: auto; background: #edf9f5; }
header { position: sticky; top: 0; z-index: 20; display: flex; align-items: center; gap: 12px; padding: 16px 20px 10px; background: rgba(255,255,255,.95); backdrop-filter: blur(18px); }
header h1 { margin: 0; color: #1d1d1f; font-size: 20px; font-weight: 700; letter-spacing: -.01em; } header p { margin: 2px 0 0; color: #8e8e93; font-size: 13px; font-weight: 500; }
main { padding: 8px 22px 30px; }
.publisher-list { overflow: hidden; border-radius: 17px; background: white; }
.publisher-row { display: grid; grid-template-columns: 45px 1fr auto; gap: 12px; align-items: center; min-height: 78px; margin-left: 16px; padding: 12px 15px 12px 0; border-bottom: 1px solid #dedee2; }
.publisher-row:last-child { border-bottom: 0; }
.publisher-mark { width: 45px; height: 45px; border-radius: 11px; display: grid; place-items: center; color: white; background: linear-gradient(145deg,#1584c7,#0a466e); font-size: 18px; font-weight: 800; }
.publisher-row:nth-child(3n+2) .publisher-mark { background: linear-gradient(145deg,#c28d24,#6d4614); }.publisher-row:nth-child(3n) .publisher-mark { background: linear-gradient(145deg,#ea4e59,#9e0e23); }
.publisher-copy strong, .publisher-copy small { display: block; }.publisher-copy strong { font-size: 17px; }.publisher-copy small { margin-top: 2px; color: #8e8e93; font-size: 13px; }
</style>
