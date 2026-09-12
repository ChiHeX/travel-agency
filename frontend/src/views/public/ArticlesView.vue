<script setup>
import { computed, inject, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { contentApi } from '@/api/modules'
import AppIcon from '@/components/AppIcon.vue'
import RequestState from '@/components/RequestState.vue'

const router = useRouter()
const closeDrawer = inject('closeDrawer', () => {})
const articles = ref([])
const loading = ref(true)
const error = ref('')
const activeRegion = ref('')
const activeCity = ref('')
const failedImages = ref(new Set())
const showScopeMenu = ref(false)
const openRegion = ref('')
const wideSubmenu = ref(false)
const submenuPosition = ref({ top: '0px', left: '0px', width: '320px' })

const cities = computed(() => [...new Set(articles.value.map((item) => item.city).filter(Boolean))])
const cityCards = computed(() => cities.value.map((city) => {
  const cityArticles = articles.value.filter((item) => item.city === city)
  return { city, count: cityArticles.length, coverArticle: cityArticles.find((item) => item.coverUrl) || null }
}))
const regionGroups = computed(() => {
  const groups = new Map()
  for (const article of articles.value) {
    const region = article.destination || article.city
    if (!region) continue
    const group = groups.get(region) || { name: region, cities: new Set() }
    if (article.city) group.cities.add(article.city)
    groups.set(region, group)
  }
  return [...groups.values()].map((group) => ({ name: group.name, cities: [...group.cities] }))
})
const visibleArticles = computed(() => {
  if (activeCity.value) return articles.value.filter((item) => item.city === activeCity.value)
  if (activeRegion.value) return articles.value.filter((item) => (item.destination || item.city) === activeRegion.value)
  return articles.value
})
const scopeLabel = computed(() => activeCity.value || activeRegion.value || '全球')
const featured = computed(() => visibleArticles.value[0])
const latest = computed(() => visibleArticles.value.slice(0, 4))

async function load() {
  loading.value = true
  error.value = ''
  try {
    const result = await contentApi.articles({ page: 1, size: 100 })
    articles.value = result?.items || []
  } catch (cause) {
    error.value = cause.message || '指南加载失败'
  } finally {
    loading.value = false
  }
}

function openArticle(id) {
  router.push({ name: 'article-detail', params: { id } })
}

function markImageFailed(id) {
  failedImages.value = new Set(failedImages.value).add(String(id))
}

function selectGlobal() {
  activeRegion.value = ''
  activeCity.value = ''
  closeScopeMenu()
}

function selectRegion(group, event) {
  if (group.cities.length) {
    if (openRegion.value === group.name && wideSubmenu.value) {
      openRegion.value = ''
      wideSubmenu.value = false
      return
    }
    openRegion.value = group.name
    wideSubmenu.value = window.innerWidth > 900
    if (wideSubmenu.value) {
      const rect = event.currentTarget.getBoundingClientRect()
      const width = Math.min(360, Math.max(280, window.innerWidth * 0.3))
      submenuPosition.value = {
        top: `${Math.min(rect.top, window.innerHeight - 260)}px`,
        left: `${Math.min(rect.right + 12, window.innerWidth - width - 18)}px`,
        width: `${width}px`
      }
    }
    return
  }
  activeRegion.value = group.name
  activeCity.value = ''
  closeScopeMenu()
}

function openCity(city) {
  closeScopeMenu()
  router.push({ name: 'city-guides', params: { city } })
}

function closeScopeMenu() {
  showScopeMenu.value = false
  openRegion.value = ''
  wideSubmenu.value = false
}

function toggleScopeMenu() {
  showScopeMenu.value = !showScopeMenu.value
  if (!showScopeMenu.value) {
    openRegion.value = ''
    wideSubmenu.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="guide-home">
    <header class="guide-header">
      <div>
        <h1>指南</h1>
        <button type="button" class="scope-label" :aria-expanded="showScopeMenu" aria-haspopup="menu" @click="toggleScopeMenu">
          <span>{{ scopeLabel }}</span><AppIcon name="chevron-down" size="15" />
        </button>
      </div>
      <button type="button" class="circle-button" aria-label="关闭指南" @click="closeDrawer"><AppIcon name="close" size="17" /></button>
    </header>

    <div v-if="showScopeMenu" class="scope-menu-layer" @click.self="closeScopeMenu">
      <div class="scope-menu" role="menu" aria-label="指南浏览范围">
        <template v-if="!openRegion || wideSubmenu">
          <button type="button" role="menuitem" @click="selectGlobal"><span>全球</span></button>
          <button v-for="group in regionGroups" :key="group.name" type="button" role="menuitem" :class="{ selected: openRegion === group.name }" @click="selectRegion(group, $event)">
            <span>{{ group.name }}</span><AppIcon v-if="group.cities.length" name="chevron-right" size="20" />
          </button>
        </template>
        <template v-else>
          <button type="button" class="menu-back" role="menuitem" @click="openRegion = ''"><AppIcon name="chevron-left" size="18" /><strong>{{ openRegion }}</strong></button>
          <button type="button" role="menuitem" @click="activeRegion = openRegion; activeCity = ''; closeScopeMenu()"><span>全部</span></button>
          <button v-for="city in regionGroups.find((group) => group.name === openRegion)?.cities || []" :key="city" type="button" role="menuitem" @click="openCity(city)"><span>{{ city }}</span></button>
        </template>
      </div>
    </div>

    <Teleport to="body">
      <div v-if="showScopeMenu && openRegion && wideSubmenu" class="scope-submenu" :style="submenuPosition" role="menu" :aria-label="`${openRegion}城市`">
        <button v-for="city in regionGroups.find((group) => group.name === openRegion)?.cities || []" :key="city" type="button" role="menuitem" @click="openCity(city)">
          <span>{{ city }}</span><small>{{ openRegion }}</small>
        </button>
      </div>
    </Teleport>

    <main class="guide-scroll">
      <RequestState v-if="error" :error="error" @retry="load" />
      <div v-else-if="loading" class="loading-block"><el-skeleton :rows="9" animated /></div>
      <template v-else-if="visibleArticles.length">
        <button v-if="featured" type="button" class="hero-guide" @click="openArticle(featured.id)">
          <div class="media-fallback"><AppIcon name="guides" size="34" /></div>
          <img v-if="featured.coverUrl && !failedImages.has(String(featured.id))" :src="featured.coverUrl" :alt="featured.title" @error="markImageFailed(featured.id)" />
          <span class="hero-shade"></span>
          <span class="hero-copy"><small>{{ featured.authorName }}</small><strong>{{ featured.title }}</strong></span>
        </button>

        <section v-if="latest.length" class="content-section">
          <RouterLink class="section-title" :to="{ name: 'latest-guides' }"><h2>最新</h2><AppIcon name="chevron-right" size="16" color="#8e8e93" /></RouterLink>
          <div class="latest-grid">
            <button v-for="article in latest" :key="article.id" type="button" class="portrait-card" @click="openArticle(article.id)">
              <div class="media-fallback"><AppIcon name="guides" size="26" /></div>
              <img v-if="article.coverUrl && !failedImages.has(String(article.id))" :src="article.coverUrl" :alt="article.title" @error="markImageFailed(article.id)" />
              <span class="card-shade"></span>
              <span class="card-copy"><small>{{ article.authorName }}</small><strong>{{ article.title }}</strong></span>
            </button>
          </div>
        </section>

        <section v-if="cityCards.length" class="content-section city-section">
          <h2>城市</h2>
          <div class="city-grid">
            <button v-for="card in cityCards" :key="card.city" type="button" @click="openCity(card.city)">
              <img v-if="card.coverArticle && !failedImages.has(String(card.coverArticle.id))" :src="card.coverArticle.coverUrl" :alt="card.city" @error="markImageFailed(card.coverArticle.id)" />
              <i></i><span>{{ card.city }}</span><small>{{ card.count }} 个指南</small>
            </button>
          </div>
        </section>

        <RouterLink class="publisher-entry" :to="{ name: 'article-publishers' }">
          <span class="publisher-icon"><AppIcon name="guides" size="20" /></span>
          <span><strong>按发布者浏览</strong><small>查看每位编辑与机构发布的全部指南</small></span>
          <AppIcon name="chevron-right" size="18" color="#8e8e93" />
        </RouterLink>
      </template>
      <RequestState v-else empty empty-text="暂无已发布指南" />
    </main>
  </div>
</template>

<style scoped>
.guide-home { position: relative; display: flex; flex-direction: column; height: 100%; background: #f7faf9; color: #111; }
.guide-header { position: relative; z-index: 22; display: flex; align-items: flex-start; justify-content: space-between; min-height: 88px; padding: 22px 22px 10px; }
.guide-header h1 { margin: 0; font-size: 30px; line-height: 1; letter-spacing: -.04em; }
.scope-label { display: inline-flex; align-items: center; gap: 2px; margin-top: 5px; margin-left: -3px; padding: 2px 4px; border: 0; border-radius: 8px; color: var(--theme-blue); background: transparent; font-size: 17px; font-weight: 700; line-height: 1.1; cursor: pointer; }.scope-label svg { margin-top: 2px; transition: transform .18s ease; }.scope-label[aria-expanded="true"] svg { transform: rotate(180deg); }.scope-label:hover { background: rgba(0,113,227,.07); }
.circle-button { width: 38px; height: 38px; border: 0; border-radius: 50%; display: grid; place-items: center; color: #7d8185; background: rgba(0,0,0,.055); cursor: pointer; }
.scope-menu-layer { position: absolute; z-index: 21; inset: 80px 0 0; padding: 10px 18px; background: rgba(245,249,248,.18); }
.scope-menu { max-height: min(410px, calc(100% - 18px)); overflow-y: auto; border: 1px solid rgba(0,0,0,.05); border-radius: 21px; background: rgba(255,255,255,.97); box-shadow: 0 10px 32px rgba(0,0,0,.2); backdrop-filter: blur(24px) saturate(150%); }
.scope-menu button { display: flex; align-items: center; justify-content: space-between; width: 100%; min-height: 72px; padding: 0 26px; border: 0; color: #1d1d1f; background: transparent; font-size: 18px; text-align: left; cursor: pointer; }.scope-menu button:hover, .scope-menu button.selected { background: #f2f2f7; }.scope-menu .menu-back { justify-content: flex-start; gap: 8px; border-bottom: 1px solid #e5e5e7; color: var(--theme-blue); }.scope-menu .menu-back strong { color: #1d1d1f; }
.scope-submenu { position: fixed; z-index: 100; max-height: 360px; overflow-y: auto; padding: 12px 0; border: 1px solid rgba(0,0,0,.05); border-radius: 21px; background: rgba(255,255,255,.97); box-shadow: 0 10px 32px rgba(0,0,0,.2); backdrop-filter: blur(24px) saturate(150%); }.scope-submenu button { display: grid; gap: 0; width: 100%; min-height: 76px; padding: 12px 26px; border: 0; background: transparent; text-align: left; cursor: pointer; }.scope-submenu button:hover { background: #f2f2f7; }.scope-submenu span { color: #1d1d1f; font-size: 18px; }.scope-submenu small { color: #737378; font-size: 16px; }
.guide-scroll { flex: 1; min-height: 0; overflow-y: auto; padding: 6px 22px 32px; }
.loading-block { padding: 10px 0; }
.hero-guide, .portrait-card { position: relative; overflow: hidden; border: 0; color: white; background: #dceaf1; text-align: left; cursor: pointer; }
.hero-guide { width: 100%; height: 205px; border-radius: 18px; }
.hero-guide img, .portrait-card img, .media-fallback { width: 100%; height: 100%; object-fit: cover; }.hero-guide img, .portrait-card img { position: absolute; inset: 0; }
.media-fallback { display: grid; place-items: center; color: #2976a8; background: linear-gradient(145deg,#bde7f1,#dce9d0); }
.hero-shade, .card-shade { position: absolute; inset: 0; background: linear-gradient(0deg, rgba(0,0,0,.72), transparent 62%); }
.hero-copy, .card-copy { position: absolute; right: 16px; bottom: 16px; left: 16px; display: grid; gap: 3px; }
.hero-copy small, .card-copy small { font-size: 11px; font-weight: 650; }
.hero-copy strong { font-size: 21px; line-height: 1.1; letter-spacing: -.02em; }
.content-section { margin-top: 28px; }
.section-title { display: inline-flex; align-items: center; gap: 2px; margin-bottom: 12px; border-radius: 7px; }.section-title:hover { color: var(--theme-blue); }
.content-section h2 { margin: 0 0 12px; font-size: 20px; letter-spacing: -.025em; }
.section-title h2 { margin: 0; }
.latest-grid { display: grid; grid-auto-flow: column; grid-auto-columns: 72%; gap: 10px; overflow-x: auto; scroll-snap-type: x mandatory; scrollbar-width: none; }
.latest-grid::-webkit-scrollbar { display: none; }
.portrait-card { height: 238px; border-radius: 17px; scroll-snap-align: start; }
.card-copy strong { font-size: 17px; line-height: 1.16; }
.city-section { margin-right: -22px; margin-left: -22px; padding: 22px; background: #f1f1ef; }
.city-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
.city-grid button { position: relative; min-height: 112px; padding: 16px; overflow: hidden; border: 0; border-radius: 16px; display: grid; align-content: end; justify-items: start; color: white; background: linear-gradient(145deg,#5f9fbd,#315f77); text-align: left; cursor: pointer; }.city-grid button img, .city-grid button i { position: absolute; inset: 0; width: 100%; height: 100%; }.city-grid button img { object-fit: cover; }.city-grid button i { background: linear-gradient(0deg,rgba(0,0,0,.6),transparent 70%); }
.city-grid button:nth-child(2n) { background: linear-gradient(145deg,#807766,#464238); }
.city-grid span, .city-grid small { position: relative; }.city-grid span { font-size: 18px; font-weight: 750; }.city-grid small { opacity: .8; }
.publisher-entry { display: grid; grid-template-columns: 42px 1fr auto; align-items: center; gap: 12px; margin-top: 22px; padding: 14px; border-radius: 16px; background: white; }
.publisher-icon { width: 42px; height: 42px; border-radius: 12px; display: grid; place-items: center; color: var(--theme-blue); background: var(--theme-blue-tint); }
.publisher-entry strong, .publisher-entry small { display: block; }.publisher-entry strong { font-size: 14px; }.publisher-entry small { margin-top: 3px; color: #8e8e93; font-size: 11px; }
@media (max-width: 900px) { .guide-header { padding-top: 16px; }.guide-scroll { padding-bottom: calc(32px + env(safe-area-inset-bottom)); }.hero-guide { height: 225px; }.latest-grid { grid-auto-columns: 67%; } }
</style>
