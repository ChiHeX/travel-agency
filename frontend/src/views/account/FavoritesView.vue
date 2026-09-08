<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { accountApi } from '@/api/modules'
import RouteCard from '@/components/RouteCard.vue'
import AppIcon from '@/components/AppIcon.vue'

const routes = ref([])
const loading = ref(false)
const page = ref(1)
const pageSize = 12
const total = ref(0)
const errorMessage = ref('')

async function load() {
  loading.value = true
  errorMessage.value = ''
  try {
    const data = await accountApi.favorites({ page: page.value, size: pageSize })
    routes.value = data?.items || []
    total.value = data?.total || 0
  } catch (error) {
    routes.value = []
    total.value = 0
    errorMessage.value = error.message || '收藏列表加载失败'
  } finally {
    loading.value = false
  }
}

async function remove(id) {
  await accountApi.removeFavorite(id)
  routes.value = routes.value.filter((item) => item.id !== id)
  total.value = Math.max(0, total.value - 1)
  ElMessage.success('已从心愿收藏中移除')
  if (!routes.value.length && page.value > 1) {
    page.value -= 1
    load()
  }
}

function changePage(nextPage) {
  page.value = nextPage
  load()
}

onMounted(load)
</script>

<template>
  <div class="account-page">
    <div class="container page-section">
      <div class="section-head">
        <div>
          <span class="eyebrow">SAVED EXPERIENCES</span>
          <h2>我的心愿收藏</h2>
          <p>收藏您心仪的跟团游路线，随时查看最新团期与特惠价格。</p>
        </div>
      </div>

      <div v-if="loading" class="favorites-grid">
        <div v-for="i in 3" :key="i" class="skeleton-card">
          <el-skeleton :rows="4" animated />
        </div>
      </div>

      <div v-else-if="errorMessage" class="empty-box favorite-error">
        <strong>收藏暂时无法加载</strong>
        <span>{{ errorMessage }}</span>
        <button type="button" class="secondary-button" @click="load">重新加载</button>
      </div>

      <div v-else-if="routes.length" class="favorites-grid">
        <div v-for="item in routes" :key="item.id" class="favorite-item-wrapper">
          <RouteCard :route="item" />
          <button
            type="button"
            class="remove-fav-btn"
            title="取消收藏"
            @click.stop="remove(item.id)"
          >
            <AppIcon name="heart-filled" size="13" color="#ff3b30" />
            <span>已收藏</span>
          </button>
        </div>
      </div>

      <div v-else class="empty-box">
        暂无收藏路线，在线路详情页点击收藏按钮即可添加至此。
      </div>

      <div v-if="!loading && !errorMessage && total > pageSize" class="pagination-wrap">
        <el-pagination background layout="prev, pager, next" :current-page="page" :page-size="pageSize" :total="total" @current-change="changePage" />
      </div>
    </div>
  </div>
</template>

<style scoped>
.account-page {
  background: var(--app-bg);
  min-height: calc(100vh - 64px);
}

.favorites-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 20px;
}

.favorite-item-wrapper {
  position: relative;
}

.favorite-error { display: grid; justify-items: center; gap: 9px; }
.favorite-error span { color: var(--text-secondary); font-size: 12px; }
.pagination-wrap { display: flex; justify-content: center; padding: 24px 0 4px; }

.remove-fav-btn {
  position: absolute;
  top: 10px;
  right: 10px;
  z-index: 2;
  background: rgba(255, 255, 255, 0.95);
  border: 1px solid var(--border-divider);
  color: var(--status-red);
  padding: 4px 10px;
  border-radius: var(--radius-pill);
  font-size: 11px;
  font-weight: 600;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  cursor: pointer;
  box-shadow: var(--shadow-card);
  transition: all 0.15s ease;
}

.remove-fav-btn:hover {
  background: var(--status-red-bg);
}

@media (max-width: 900px) {
  .favorites-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}

@media (max-width: 600px) {
  .favorites-grid {
    grid-template-columns: 1fr;
  }
}
</style>
