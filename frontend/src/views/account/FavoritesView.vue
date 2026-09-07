<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { accountApi } from '@/api/modules'
import RouteCard from '@/components/RouteCard.vue'
import AppIcon from '@/components/AppIcon.vue'

const routes = ref([])
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    routes.value = (await accountApi.favorites())?.items || []
  } finally {
    loading.value = false
  }
}

async function remove(id) {
  await accountApi.removeFavorite(id)
  routes.value = routes.value.filter((item) => item.id !== id)
  ElMessage.success('已从心愿收藏中移除')
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
