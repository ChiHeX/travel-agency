<script setup>
import { computed, inject, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { contentApi } from '@/api/modules'
import AppIcon from '@/components/AppIcon.vue'
import RequestState from '@/components/RequestState.vue'

const route = useRoute()
const router = useRouter()
const closeDrawer = inject('closeDrawer', () => {})
const articles = ref([])
const loading = ref(true)
const error = ref('')
const failedImages = ref(new Set())
const pageRoot = ref(null)
const showScopeMenu = ref(false)
const openRegion = ref('')
const wideSubmenu = ref(false)
const submenuPosition = ref({ top: '0px', left: '0px', width: '320px' })
const city = computed(() => String(route.params.city || ''))
const cityArticles = computed(() => articles.value.filter((item) => item.city === city.value))
const featured = computed(() => cityArticles.value[0] || null)
const guideCards = computed(() => cityArticles.value.slice(1, 7))
const latestCards = computed(() => cityArticles.value.slice(0, 6))
const otherCities = computed(() => {
  const groups = new Map()
  for (const article of articles.value) {
    if (!article.city || article.city === city.value) continue
    const current = groups.get(article.city) || { city: article.city, count: 0, coverArticle: null }
    current.count += 1
    if (!current.coverArticle && article.coverUrl) current.coverArticle = article
    groups.set(article.city, current)
  }
  return [...groups.values()]
})
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

async function load() {
  loading.value = true
  error.value = ''
  try {
    const first = await contentApi.articles({ page: 1, size: 100 })
    const items = [...(first?.items || [])]
    const totalPages = Math.ceil((first?.total || items.length) / 100)
    for (let page = 2; page <= totalPages; page += 1) {
      const result = await contentApi.articles({ page, size: 100 })
      items.push(...(result?.items || []))
    }
    articles.value = items
  } catch (cause) {
    error.value = cause.message || '城市指南加载失败'
  } finally {
    loading.value = false
  }
}

function markImageFailed(id) {
  failedImages.value = new Set(failedImages.value).add(String(id))
}

function openArticle(id) {
  router.push({ name: 'article-detail', params: { id } })
}

function openCity(name) {
  closeScopeMenu()
  if (name === city.value) return
  router.push({ name: 'city-guides', params: { city: name } })
}

function setSubmenuPosition(target) {
  const pageRect = pageRoot.value?.getBoundingClientRect()
  const targetRect = target?.getBoundingClientRect()
  if (!pageRect) return
  const width = Math.min(360, Math.max(280, window.innerWidth * 0.3))
  submenuPosition.value = {
    top: `${Math.min(targetRect?.top || pageRect.top + 210, window.innerHeight - 260)}px`,
    left: `${Math.min(pageRect.right + 12, window.innerWidth - width - 18)}px`,
    width: `${width}px`
  }
}

function selectGlobal() {
  closeScopeMenu()
  router.push({ name: 'articles' })
}

function selectRegion(group, event) {
  if (!group.cities.length) return
  if (openRegion.value === group.name && wideSubmenu.value) {
    openRegion.value = ''
    wideSubmenu.value = false
    return
  }
  openRegion.value = group.name
  wideSubmenu.value = window.innerWidth > 900
  if (wideSubmenu.value) setSubmenuPosition(event.currentTarget)
}

function closeScopeMenu() {
  showScopeMenu.value = false
  openRegion.value = ''
  wideSubmenu.value = false
}

function toggleScopeMenu() {
  showScopeMenu.value = !showScopeMenu.value
  if (!showScopeMenu.value) {
    closeScopeMenu()
    return
  }
  openRegion.value = ''
  wideSubmenu.value = false
}

onMounted(load)
</script>

<template>
  <div ref="pageRoot" class="city-guide-page">
    <header class="city-header">
      <div>
        <h1>指南</h1>
        <button type="button" class="city-selector" :aria-expanded="showScopeMenu" aria-haspopup="menu" aria-label="切换指南城市" @click="toggleScopeMenu">
          <span>{{ city }}</span><AppIcon name="chevron-down" size="15" />
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
          <button v-for="name in regionGroups.find((group) => group.name === openRegion)?.cities || []" :key="name" type="button" role="menuitem" :class="{ selected: name === city }" @click="openCity(name)">
            <span>{{ name }}</span><span v-if="name === city" class="checkmark" aria-label="当前城市">✓</span>
          </button>
        </template>
      </div>
    </div>

    <Teleport to="body">
      <div v-if="showScopeMenu && openRegion && wideSubmenu" class="city-scope-submenu" :style="submenuPosition" role="menu" :aria-label="`${openRegion}城市`">
        <button v-for="name in regionGroups.find((group) => group.name === openRegion)?.cities || []" :key="name" type="button" role="menuitem" :class="{ selected: name === city }" @click="openCity(name)">
          <span><strong>{{ name }}</strong><small>{{ openRegion }}</small></span><span v-if="name === city" class="checkmark" aria-label="当前城市">✓</span>
        </button>
      </div>
    </Teleport>

    <main>
      <RequestState v-if="error" :error="error" @retry="load" />
      <div v-else-if="loading" class="loading-block"><el-skeleton :rows="12" animated /></div>
      <template v-else-if="cityArticles.length">
        <button v-if="featured" type="button" class="hero-card" @click="openArticle(featured.id)">
          <div class="media-fallback"><AppIcon name="guides" size="34" /></div>
          <img v-if="featured.coverUrl && !failedImages.has(String(featured.id))" :src="featured.coverUrl" :alt="featured.title" @error="markImageFailed(featured.id)" />
          <i></i>
          <span><small>{{ featured.authorName }}</small><strong>{{ featured.title }}</strong></span>
        </button>

        <section v-if="guideCards.length" class="guide-section">
          <h2>旅行指南</h2>
          <div class="card-row">
            <button v-for="article in guideCards" :key="article.id" type="button" class="guide-card" @click="openArticle(article.id)">
              <div class="media-fallback"><AppIcon name="guides" size="28" /></div>
              <img v-if="article.coverUrl && !failedImages.has(String(article.id))" :src="article.coverUrl" :alt="article.title" @error="markImageFailed(article.id)" />
              <i></i><span><small>{{ article.authorName }}</small><strong>{{ article.title }}</strong></span>
            </button>
          </div>
        </section>

        <section class="guide-section">
          <RouterLink class="section-title" :to="{ name: 'latest-guides', query: { city } }">
            <h2>最新</h2><AppIcon name="chevron-right" size="16" color="#8e8e93" />
          </RouterLink>
          <div class="card-row">
            <button v-for="article in latestCards" :key="article.id" type="button" class="guide-card" @click="openArticle(article.id)">
              <div class="media-fallback"><AppIcon name="guides" size="28" /></div>
              <img v-if="article.coverUrl && !failedImages.has(String(article.id))" :src="article.coverUrl" :alt="article.title" @error="markImageFailed(article.id)" />
              <i></i><span><small>{{ article.authorName }}</small><strong>{{ article.title }}</strong></span>
            </button>
          </div>
        </section>

        <section v-if="otherCities.length" class="guide-section cities-section">
          <h2>城市</h2>
          <div class="city-grid">
            <button v-for="item in otherCities" :key="item.city" type="button" @click="openCity(item.city)">
              <img v-if="item.coverArticle && !failedImages.has(String(item.coverArticle.id))" :src="item.coverArticle.coverUrl" :alt="item.city" @error="markImageFailed(item.coverArticle.id)" />
              <i></i><span>{{ item.city }}</span><small>{{ item.count }} 个指南</small>
            </button>
          </div>
        </section>
      </template>
      <RequestState v-else empty :empty-text="`${city}暂无已发布指南`" />
    </main>
  </div>
</template>

<style scoped>
.city-guide-page { position: relative; height: 100%; overflow-y: auto; color: #111; background: #f7faf9; }
.city-header { position: sticky; z-index: 5; top: 0; display: flex; align-items: flex-start; justify-content: space-between; min-height: 88px; padding: 22px 22px 10px; background: rgba(247,250,249,.9); backdrop-filter: blur(18px) saturate(150%); }
.city-header h1 { margin: 0; font-size: 30px; line-height: 1; letter-spacing: -.04em; }
.city-selector { display: inline-flex; align-items: center; gap: 2px; margin: 5px 0 0 -3px; padding: 2px 4px; border: 0; border-radius: 8px; color: var(--theme-blue); background: transparent; font-size: 17px; font-weight: 700; cursor: pointer; }
.city-selector svg { margin-top: 2px; transition: transform .18s ease; }.city-selector[aria-expanded="true"] svg { transform: rotate(180deg); }.city-selector:hover { background: rgba(0,113,227,.07); }
.circle-button { width: 38px; height: 38px; border: 0; border-radius: 50%; display: grid; place-items: center; color: #7d8185; background: rgba(0,0,0,.055); cursor: pointer; }
.scope-menu-layer { position: absolute; z-index: 4; inset: 80px 0 0; padding: 10px 18px; background: rgba(245,249,248,.18); }
.scope-menu { max-height: min(410px, calc(100% - 18px)); overflow-y: auto; border: 1px solid rgba(0,0,0,.05); border-radius: 21px; background: rgba(255,255,255,.97); box-shadow: 0 10px 32px rgba(0,0,0,.2); backdrop-filter: blur(24px) saturate(150%); }
.scope-menu button { display: flex; align-items: center; justify-content: space-between; width: 100%; min-height: 72px; padding: 0 26px; border: 0; color: #1d1d1f; background: transparent; font-size: 18px; text-align: left; cursor: pointer; }.scope-menu button:hover, .scope-menu button.selected { background: #f2f2f7; }.scope-menu .menu-back { justify-content: flex-start; gap: 8px; border-bottom: 1px solid #e5e5e7; color: var(--theme-blue); }.scope-menu .menu-back strong { color: #1d1d1f; }.checkmark { margin-left: auto; font-size: 25px; font-weight: 400; }
:global(.city-scope-submenu) { position: fixed; z-index: 100; max-height: 360px; overflow-y: auto; padding: 10px; border: 1px solid rgba(0,0,0,.05); border-radius: 21px; background: rgba(255,255,255,.97); box-shadow: 0 10px 32px rgba(0,0,0,.2); backdrop-filter: blur(24px) saturate(150%); }
:global(.city-scope-submenu button) { display: flex; align-items: center; justify-content: space-between; width: 100%; min-height: 94px; padding: 12px 18px; border: 0; border-radius: 16px; background: transparent; text-align: left; cursor: pointer; }
:global(.city-scope-submenu button:hover), :global(.city-scope-submenu button.selected) { background: #f2f2f7; }:global(.city-scope-submenu button > span:first-child) { display: grid; }:global(.city-scope-submenu strong) { color: #1d1d1f; font-size: 18px; font-weight: 500; }:global(.city-scope-submenu small) { color: #737378; font-size: 16px; }:global(.city-scope-submenu .checkmark) { color: #1d1d1f; font-size: 25px; }
main { padding: 8px 22px 34px; }.loading-block { padding-top: 8px; }
.hero-card, .guide-card { position: relative; overflow: hidden; border: 0; color: white; background: #cbe7ec; text-align: left; cursor: pointer; }
.hero-card { width: 100%; height: 205px; border-radius: 18px; }
.hero-card img, .guide-card img, .media-fallback { position: absolute; inset: 0; width: 100%; height: 100%; object-fit: cover; }
.media-fallback { display: grid; place-items: center; color: #2976a8; background: linear-gradient(145deg,#bde7f1,#dce9d0); }
.hero-card > i, .guide-card > i { position: absolute; inset: 0; background: linear-gradient(0deg,rgba(0,0,0,.72),transparent 64%); }
.hero-card > span, .guide-card > span { position: absolute; right: 16px; bottom: 15px; left: 16px; display: grid; gap: 3px; }
.hero-card small, .guide-card small { font-size: 11px; font-weight: 650; }.hero-card strong { font-size: 21px; line-height: 1.12; }.guide-card strong { font-size: 16px; line-height: 1.14; }
.guide-section { margin-top: 28px; }.guide-section h2 { margin: 0 0 12px; font-size: 20px; letter-spacing: -.025em; }
.section-title { display: inline-flex; align-items: center; gap: 2px; margin-bottom: 12px; border-radius: 7px; }.section-title h2 { margin: 0; }.section-title:hover { color: var(--theme-blue); }
.card-row { display: grid; grid-auto-flow: column; grid-auto-columns: calc(50% - 5px); gap: 10px; overflow-x: auto; scroll-snap-type: x mandatory; scrollbar-width: none; }.card-row::-webkit-scrollbar { display: none; }
.guide-card { height: 220px; border-radius: 17px; scroll-snap-align: start; }
.cities-section { margin-right: -22px; margin-left: -22px; padding: 22px; background: #f1f1ef; }
.city-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
.city-grid button { position: relative; min-height: 112px; padding: 16px; overflow: hidden; border: 0; border-radius: 16px; display: grid; align-content: end; justify-items: start; color: white; background: linear-gradient(145deg,#5f9fbd,#315f77); text-align: left; cursor: pointer; }
.city-grid button img, .city-grid button i { position: absolute; inset: 0; width: 100%; height: 100%; }.city-grid button img { object-fit: cover; }.city-grid button i { background: linear-gradient(0deg,rgba(0,0,0,.62),transparent 70%); }
.city-grid span, .city-grid small { position: relative; }.city-grid span { font-size: 18px; font-weight: 750; }.city-grid small { opacity: .82; }
@media (max-width: 900px) { .city-header { padding-top: 16px; }.hero-card { height: 225px; }.guide-card { height: 238px; }main { padding-bottom: calc(34px + env(safe-area-inset-bottom)); } }
</style>
