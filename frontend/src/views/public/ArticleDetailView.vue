<script setup>
import { inject, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { contentApi } from '@/api/modules'
import AppIcon from '@/components/AppIcon.vue'

const route = useRoute()
const router = useRouter()
const closeDrawer = inject('closeDrawer', () => {})
const article = ref(null)
const loading = ref(true)

onMounted(async () => {
  try {
    article.value = await contentApi.article(route.params.id)
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div class="article-drawer-panel">
    <!-- Top Bar: 返回 ✕ -->
    <div class="drawer-header-bar">
      <button type="button" class="back-link-btn" @click="router.push('/articles')">
        <AppIcon name="chevron-left" size="14" />
        <span>全部指南</span>
      </button>
      <button type="button" class="drawer-close-btn" title="关闭面板" @click="closeDrawer">
        <AppIcon name="close" size="13" />
      </button>
    </div>

    <div v-if="loading" class="loading-wrap">
      <el-skeleton :rows="8" animated />
    </div>

    <div v-else-if="article" class="drawer-scroll-body">
      <!-- Article Hero Cover -->
      <div v-if="article.coverUrl" class="article-cover-img">
        <img :src="article.coverUrl" :alt="article.title" />
      </div>

      <div class="article-meta">
        <span class="city-tag">{{ article.city || article.destination || 'GUIDE' }}</span>
        <h2>{{ article.title }}</h2>
        <span class="pub-time">{{ article.publishedAt || '发布日期待同步' }}</span>
      </div>

      <div v-if="article.summary" class="article-quote-box">
        <p>{{ article.summary }}</p>
      </div>

      <div class="article-body">
        <p>{{ article.content }}</p>
      </div>

      <!-- Action Card -->
      <div class="article-cta-box">
        <div class="cta-text">
          <strong>想要亲身体验？</strong>
          <span>查看 {{ article.city || article.destination }} 精品跟团路线</span>
        </div>
        <RouterLink
          :to="{ name: 'routes', query: { keyword: article.city || article.destination || '' } }"
          class="primary-button cta-btn"
        >
          <span>查看相关路线</span>
          <AppIcon name="chevron-right" size="13" />
        </RouterLink>
      </div>
    </div>

    <div v-else class="empty-box">
      文章不存在。
    </div>
  </div>
</template>

<style scoped>
.article-drawer-panel {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: transparent;
}

.drawer-header-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 14px 18px;
  border-bottom: 1px solid rgba(0, 0, 0, 0.08);
}

.back-link-btn {
  background: transparent;
  border: none;
  color: var(--theme-blue);
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 2px;
}

.drawer-close-btn {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  background: rgba(0, 0, 0, 0.05);
  border: none;
  font-size: 13px;
  color: var(--text-secondary);
  display: grid;
  place-items: center;
  cursor: pointer;
}

.loading-wrap {
  padding: 24px;
}

.drawer-scroll-body {
  flex: 1;
  overflow-y: auto;
  padding: 16px 18px 32px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.article-cover-img {
  height: 160px;
  border-radius: var(--radius-md);
  overflow: hidden;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.08);
}

.article-cover-img img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.article-meta {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.city-tag {
  font-size: 10px;
  font-weight: 700;
  color: var(--theme-blue);
  letter-spacing: 0.05em;
  text-transform: uppercase;
}

.article-meta h2 {
  font-size: 18px;
  font-weight: 700;
  color: #1d1d1f;
  margin: 0;
  line-height: 1.35;
}

.pub-time {
  font-size: 11px;
  color: var(--text-tertiary);
}

.article-quote-box {
  background: rgba(0, 0, 0, 0.04);
  border-left: 3px solid var(--theme-blue);
  border-radius: 0 var(--radius-sm) var(--radius-sm) 0;
  padding: 10px 14px;
}

.article-quote-box p {
  margin: 0;
  font-size: 12px;
  color: #1d1d1f;
  line-height: 1.6;
}

.article-body p {
  font-size: 13px;
  color: #1d1d1f;
  line-height: 1.8;
  white-space: pre-wrap;
}

.article-cta-box {
  background: var(--theme-blue-tint);
  border-radius: var(--radius-md);
  padding: 14px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.cta-text strong {
  display: block;
  font-size: 13px;
  color: #1d1d1f;
}

.cta-text span {
  font-size: 11px;
  color: var(--text-secondary);
}

.cta-btn {
  width: 100%;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
}
</style>
