<script setup>
import AppIcon from '@/components/AppIcon.vue'

defineProps({
  route: {
    type: Object,
    required: true
  }
})
</script>

<template>
  <RouterLink :to="{ name: 'route-detail', params: { id: route.id } }" class="系统-route-card">
    <div class="card-cover-box">
      <img v-if="route.coverUrl" :src="route.coverUrl" :alt="route.name" loading="lazy" />
      <div v-else class="cover-fallback">
        <span>{{ route.destination?.slice(0, 2) || '—' }}</span>
      </div>
      <span class="badge-duration">{{ route.durationDays }} 日游</span>
      <span v-if="route.ratingCount" class="badge-rating">
        <AppIcon name="star" size="10" color="#ff9500" />
        <span>{{ route.ratingAvg }}</span>
      </span>
    </div>

    <div class="card-info-box">
      <h3 :title="route.name">{{ route.name }}</h3>
      <div class="card-cities">
        <span>{{ route.departureCity }} 出发</span>
        <span>·</span>
        <span>目的地 {{ route.destination }}</span>
      </div>

      <div class="card-footer">
        <span v-if="route.ratingCount" class="tag success">{{ route.ratingCount }} 条评价</span>
        <span v-else class="tag">暂无评分</span>
        <div class="price-box">
          <template v-if="route.minAdultPrice != null">
            <strong>¥{{ route.minAdultPrice }}</strong>
            <small>起/人</small>
          </template>
          <small v-else>价格待发布</small>
        </div>
      </div>
    </div>
  </RouterLink>
</template>

<style scoped>
.系统-route-card {
  display: flex;
  flex-direction: column;
  background: #ffffff;
  border: 1px solid var(--border-divider);
  border-radius: var(--radius-md);
  overflow: hidden;
  transition: all 0.15s ease;
}

.系统-route-card:hover {
  background: var(--bg-secondary);
  border-color: #d1d1d6;
}

.card-cover-box {
  position: relative;
  width: 100%;
  aspect-ratio: 16 / 10;
  overflow: hidden;
  background: #e5e5ea;
}

.card-cover-box img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.cover-fallback {
  width: 100%;
  height: 100%;
  display: grid;
  place-items: center;
  background: var(--theme-blue);
  color: white;
  font-weight: 700;
  font-size: 18px;
}

.badge-duration {
  position: absolute;
  top: 8px;
  left: 8px;
  background: rgba(0, 0, 0, 0.65);
  color: white;
  font-size: 10px;
  font-weight: 600;
  padding: 2px 6px;
  border-radius: 4px;
}

.badge-rating {
  position: absolute;
  top: 8px;
  right: 8px;
  background: rgba(255, 255, 255, 0.92);
  color: var(--text-primary);
  font-size: 10px;
  font-weight: 700;
  padding: 2px 6px;
  border-radius: 4px;
  display: inline-flex;
  align-items: center;
  gap: 3px;
}

.card-info-box {
  padding: 12px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.card-info-box h3 {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
  margin: 0;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.card-cities {
  font-size: 11px;
  color: var(--text-secondary);
  display: flex;
  gap: 4px;
}

.card-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 6px;
}

.price-box {
  display: flex;
  align-items: baseline;
  color: var(--price-color);
}

.price-box strong {
  font-size: 15px;
  font-weight: 700;
}

.price-box small {
  font-size: 10px;
  color: var(--text-secondary);
  margin-left: 2px;
}
</style>
