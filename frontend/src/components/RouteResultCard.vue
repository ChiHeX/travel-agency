<script setup>
defineProps({
  route: {
    type: Object,
    required: true
  }
})
</script>

<template>
  <RouterLink :to="{ name: 'route-detail', params: { id: route.id } }" class="place-card-item" :class="{ 'without-cover': !route.coverUrl }">
    <img v-if="route.coverUrl" class="place-cover" :src="route.coverUrl" alt="" loading="lazy" />
    <div class="place-info">
      <h4>{{ route.destination }}</h4>
      <p class="place-route-name" :title="route.name">{{ route.name }}</p>
      <p class="place-route-meta">{{ route.departureCity }}出发 · {{ route.durationDays }}日行程</p>
      <div class="place-card-footer">
        <span v-if="route.nextDepartureDate" class="departure-date">最近团期 {{ route.nextDepartureDate }}</span>
        <span class="price-figure"><template v-if="route.minAdultPrice != null"><strong>¥{{ route.minAdultPrice }}</strong><small>起/人</small></template><small v-else>价格待发布</small></span>
      </div>
    </div>
  </RouterLink>
</template>

<style scoped>
.place-card-item { display: grid; width: 100%; grid-template-columns: 108px minmax(0, 1fr); min-height: 136px; overflow: hidden; padding: 0; border: 1px solid rgba(0,0,0,.05); border-radius: 12px; background: rgba(255,255,255,.85); box-shadow: 0 1px 3px rgba(0,0,0,.03); color: inherit; text-align: left; text-decoration: none; cursor: pointer; transition: all .15s ease; }
.place-card-item.without-cover { grid-template-columns: minmax(0, 1fr); }
.place-card-item:hover { border-color: rgba(0,0,0,.1); background: #fff; box-shadow: 0 4px 12px rgba(0,0,0,.06); transform: translateY(-1px); }
.place-card-item:focus-visible { outline: 2px solid var(--theme-blue); outline-offset: 2px; }
.place-cover { width: 100%; height: 100%; min-height: 136px; object-fit: cover; }
.place-info { display: flex; flex-direction: column; gap: 4px; min-width: 0; padding: 11px 12px; }
.place-info h4 { overflow: hidden; margin: 0; color: #1d1d1f; font-size: 16px; font-weight: 650; line-height: 1.3; text-overflow: ellipsis; white-space: nowrap; }
.place-route-name { display: -webkit-box; overflow: hidden; margin: 0; color: #3c3c43; font-size: 12px; line-height: 1.35; -webkit-box-orient: vertical; -webkit-line-clamp: 2; }
.place-route-meta { overflow: hidden; margin: 0; color: var(--text-secondary); font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }
.place-card-footer { display: flex; align-items: flex-end; justify-content: space-between; gap: 6px; margin-top: auto; }
.departure-date { overflow: hidden; color: var(--text-secondary); font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }
.price-figure { display: flex; flex-direction: column; align-items: flex-end; flex-shrink: 0; color: var(--price-color); white-space: nowrap; }
.price-figure strong { font-size: 14px; font-weight: 700; }
.price-figure small { color: var(--text-secondary); font-size: 10px; }
</style>
