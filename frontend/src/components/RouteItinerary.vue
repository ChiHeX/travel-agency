<script setup>
import { computed, ref, watch } from 'vue'
import AppIcon from '@/components/AppIcon.vue'

const props = defineProps({ days: { type: Array, default: () => [] }, routeId: { type: String, default: '' } })
const expanded = ref([])
watch(() => props.days, (days) => { expanded.value = days.length ? [days[0].id] : [] }, { immediate: true })
const allExpanded = computed(() => props.days.length > 0 && props.days.every((day) => expanded.value.includes(day.id)))
function toggle(id) {
  expanded.value = expanded.value.includes(id) ? expanded.value.filter((value) => value !== id) : [...expanded.value, id]
}
function toggleAll() { expanded.value = allExpanded.value ? [] : props.days.map((day) => day.id) }
function itemType(type) {
  return { ATTRACTION: '景点', MEAL: '餐食', TRANSPORT: '交通', ACTIVITY: '活动', OTHER: '其他' }[type] || '行程'
}
</script>

<template>
  <div v-if="days.length" class="route-itinerary">
    <div class="itinerary-toolbar">
      <span>按天查看游览与住宿安排</span>
      <button type="button" @click="toggleAll">{{ allExpanded ? '收起全部' : '展开全部' }}</button>
    </div>
    <article v-for="day in days" :key="day.id" class="itinerary-day" :class="{ expanded: expanded.includes(day.id) }">
      <button type="button" class="day-heading" :aria-expanded="expanded.includes(day.id)" @click="toggle(day.id)">
        <span class="day-number">D{{ day.dayNumber }}</span>
        <span class="day-heading-copy">
          <strong>{{ day.title }}</strong>
          <span>{{ day.items?.length ? `${day.items.length} 项行程` : '当日安排' }} · {{ day.hotelName || '住宿安排暂未提供' }}</span>
        </span>
        <AppIcon name="chevron-down" :size="16" class="day-chevron" aria-hidden="true" />
      </button>
      <div v-if="expanded.includes(day.id)" class="day-body">
        <p class="day-description">{{ day.description || '暂无当日行程说明。' }}</p>
        <ol v-if="day.items?.length" class="day-timeline">
          <li v-for="entry in day.items" :key="entry.id">
            <span class="entry-type">{{ itemType(entry.itemType) }}</span>
            <strong>{{ entry.name }}</strong>
            <p v-if="entry.description">{{ entry.description }}</p>
          </li>
        </ol>
        <div class="day-services">
          <div><AppIcon name="bus" size="16" /><span>交通</span><p>{{ day.transportation || '交通安排暂未提供' }}</p></div>
          <div><AppIcon name="food" size="16" /><span>餐食</span><p>{{ day.meals || '餐食安排暂未提供' }}</p></div>
        </div>
        <section class="day-accommodation" aria-label="当晚住宿">
          <div class="accommodation-heading"><AppIcon name="hotel" size="16" /><h5>当晚住宿</h5></div>
          <div v-if="day.hotelName" class="hotel-card">
            <div class="hotel-placeholder" aria-hidden="true"><AppIcon name="hotel" size="28" /></div>
            <div class="hotel-copy"><span>行程安排酒店</span><strong>{{ day.hotelName }}</strong></div>
            <RouterLink v-if="routeId && day.hotelId" :to="{ name: 'hotel-detail', params: { routeId, hotelId: day.hotelId } }" class="hotel-link" :aria-label="`查看${day.hotelName}详情`"><AppIcon name="chevron-right" :size="16" /></RouterLink>
          </div>
          <p v-else class="accommodation-empty">住宿安排暂未提供</p>
        </section>
      </div>
    </article>
  </div>
  <div v-else class="empty-box">暂无已发布的每日行程。</div>
</template>

<style scoped>
.route-itinerary { display: grid; gap: 12px; }
.itinerary-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 12px; font-size: 11px; color: var(--text-tertiary); }
.itinerary-toolbar button { border: 0; background: transparent; padding: 4px 0; color: var(--theme-blue); font-size: 11px; cursor: pointer; flex-shrink: 0; }
.itinerary-day { border: 1px solid var(--border-divider); border-radius: var(--radius-md); background: #fff; overflow: hidden; }
.day-heading { width: 100%; display: flex; align-items: center; gap: 12px; border: 0; padding: 16px; background: transparent; text-align: left; cursor: pointer; color: var(--text-primary); }
.day-heading:hover { background: #fafafb; }
.day-number { display: grid; place-items: center; flex-shrink: 0; width: 36px; height: 36px; background: #f5f5f7; border-radius: 10px; font-size: 12px; font-weight: 600; }
.expanded .day-number { background: var(--theme-blue-tint); color: var(--theme-blue); }
.day-heading-copy { display: grid; gap: 5px; flex: 1; min-width: 0; overflow-wrap: anywhere; }
.day-heading-copy strong { font-size: 13px; line-height: 1.5; font-weight: 600; }
.day-heading-copy > span { color: var(--text-tertiary); font-size: 10px; line-height: 1.5; }
.day-chevron { flex-shrink: 0; color: var(--text-tertiary); transition: transform .15s ease; }
.expanded .day-chevron { transform: rotate(180deg); }
.day-body { padding: 0 16px 16px; overflow-wrap: anywhere; }
.day-description { margin: 0 0 20px; font-size: 12px; line-height: 1.8; color: var(--text-secondary); white-space: pre-wrap; }
.day-timeline { list-style: none; padding: 0 0 0 12px; margin: 0 0 20px 4px; border-left: 1px solid #dce8f5; }
.day-timeline li { position: relative; padding: 0 0 20px 8px; }
.day-timeline li:last-child { padding-bottom: 0; }
.day-timeline li::before { content: ''; position: absolute; top: 6px; left: -16px; width: 7px; height: 7px; border: 2px solid #fff; border-radius: 50%; background: var(--theme-blue); }
.entry-type { margin-right: 8px; color: var(--theme-blue); font-size: 10px; }
.day-timeline strong { font-size: 12px; font-weight: 500; }
.day-timeline p { margin: 6px 0 0; color: var(--text-secondary); font-size: 11px; line-height: 1.7; white-space: pre-wrap; }
.day-services { display: grid; gap: 12px; padding: 16px 0; border-top: 1px solid var(--border-divider); }
.day-services > div { display: grid; grid-template-columns: 16px 28px minmax(0, 1fr); gap: 8px; align-items: start; font-size: 11px; color: var(--text-secondary); line-height: 1.7; }
.day-services p { margin: 0; white-space: pre-wrap; }
.day-accommodation { padding: 14px; border-radius: 10px; background: #f7f8fa; }
.accommodation-heading { display: flex; align-items: center; gap: 7px; color: var(--text-secondary); margin-bottom: 12px; }
.accommodation-heading h5 { margin: 0; font-size: 11px; font-weight: 500; }
.hotel-card { display: flex; align-items: center; gap: 12px; }
.hotel-link { display: grid; place-items: center; margin-left: auto; width: 32px; height: 32px; flex-shrink: 0; border-radius: 50%; color: var(--theme-blue); background: #fff; }
.hotel-link:hover { background: var(--theme-blue-tint); }
.hotel-placeholder { display: grid; place-items: center; flex-shrink: 0; width: 64px; height: 64px; border-radius: 8px; background: #edf0f5; color: #8c99ac; }
.hotel-copy { display: grid; gap: 6px; min-width: 0; }
.hotel-copy span { font-size: 10px; color: var(--text-tertiary); }
.hotel-copy strong { font-size: 13px; font-weight: 500; line-height: 1.5; }
.accommodation-empty { margin: 0; color: var(--text-tertiary); font-size: 11px; }
.day-heading:focus-visible, .itinerary-toolbar button:focus-visible { outline: 2px solid var(--theme-blue); outline-offset: -2px; }
@media (max-width: 480px) { .day-heading { padding: 14px 12px; gap: 10px; } .day-body { padding: 0 12px 14px; } }
</style>
