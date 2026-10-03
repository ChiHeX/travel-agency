<script setup>
import { computed, ref, watch } from 'vue'
import AppIcon from '@/components/AppIcon.vue'
import {
  accommodationSummary,
  accommodationTypeOf,
  breakfastLabel,
  starRatingLabel
} from '@/utils/hotel'

// 住宿按后台类型展示，连续入住集中展示。
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

function hotelNameOf(day) {
  return day.hotelName || day.hotel?.name || ''
}

function hotelLocationOf(hotel) {
  return [hotel?.city, hotel?.address].filter((value) => typeof value === 'string' && value.trim() !== '').join(' · ')
}

function hotelIdOf(day) { return day.hotelId || day.hotel?.id }
function continuesStay(day, previous) {
  return accommodationTypeOf(day) === 'HOTEL' && previous &&
    accommodationTypeOf(previous) === 'HOTEL' && hotelIdOf(day) &&
    hotelIdOf(day) === hotelIdOf(previous) && day.dayNumber === previous.dayNumber + 1
}
function dailyAccommodation(day, index) {
  if (accommodationTypeOf(day) !== 'HOTEL') return accommodationSummary(day)
  return (continuesStay(day, props.days[index - 1]) ? '续住 ' : '') + (hotelNameOf(day) || '指定酒店（酒店名称暂未提供）')
}
function nightRange(days) {
  const first = days[0].dayNumber
  const last = days[days.length - 1].dayNumber
  return first === last ? '第 ' + first + ' 晚' : '第 ' + first + '–' + last + ' 晚'
}
const hotelStays = computed(() => {
  const stays = []
  props.days.forEach((day, index) => {
    if (accommodationTypeOf(day) !== 'HOTEL') return
    if (continuesStay(day, props.days[index - 1])) stays[stays.length - 1].days.push(day)
    else stays.push({ days: [day] })
  })
  return stays.map((stay) => {
    const details = []
    for (const day of stay.days) {
      const previous = details[details.length - 1]
      if (previous && previous.day.roomType === day.roomType &&
        previous.day.breakfastIncluded === day.breakfastIncluded &&
        previous.day.accommodationNote === day.accommodationNote) previous.days.push(day)
      else details.push({ day, days: [day] })
    }
    return { ...stay, day: stay.days[0], details }
  })
})
</script>

<template>
  <div v-if="days.length" class="route-itinerary">
    <div class="itinerary-toolbar">
      <span>按天查看游览与住宿安排</span>
      <button type="button" @click="toggleAll">{{ allExpanded ? '收起全部' : '展开全部' }}</button>
    </div>
    <article v-for="(day, dayIndex) in days" :key="day.id" class="itinerary-day" :class="{ expanded: expanded.includes(day.id) }">
      <button type="button" class="day-heading" :aria-expanded="expanded.includes(day.id)" @click="toggle(day.id)">
        <span class="day-number">D{{ day.dayNumber }}</span>
        <span class="day-heading-copy">
          <strong>{{ day.title }}</strong>
          <span>{{ day.items?.length ? `${day.items.length} 项行程` : '当日安排' }} · {{ dailyAccommodation(day, dayIndex) }}</span>
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
        <div class="daily-accommodation">
          <AppIcon name="hotel" size="16" /><span>住宿</span>
          <div>
            <RouterLink v-if="accommodationTypeOf(day) === 'HOTEL' && routeId && day.hotel"
              :to="{ name: 'hotel-detail', params: { routeId, hotelId: day.hotel.id } }" class="daily-hotel-link">
              {{ dailyAccommodation(day, dayIndex) }}
            </RouterLink>
            <span v-else>{{ dailyAccommodation(day, dayIndex) }}</span>
            <template v-if="accommodationTypeOf(day) === 'STANDARD'">
              <p v-if="day.accommodationStandard">住宿标准：{{ day.accommodationStandard }}</p>
              <p v-if="day.roomType">房型：{{ day.roomType }}</p>
              <p v-if="breakfastLabel(day.breakfastIncluded)">{{ breakfastLabel(day.breakfastIncluded) }}</p>
            </template>
            <p v-if="accommodationTypeOf(day) !== 'HOTEL' && day.accommodationNote">{{ day.accommodationNote }}</p>
          </div>
        </div>
      </div>
    </article>
    <section v-if="hotelStays.length" class="stay-overview" aria-label="住宿安排">
      <div class="stay-heading"><h4>住宿安排</h4></div>
      <article v-for="stay in hotelStays" :key="stay.days[0].id" class="stay-card">
        <div class="stay-period"><strong>{{ nightRange(stay.days) }}</strong><span>{{ stay.days.length }} 晚</span></div>
        <div class="hotel-card">
          <img
            v-if="stay.day.hotel?.coverUrl"
            class="hotel-cover"
            :src="stay.day.hotel.coverUrl"
            :alt="hotelNameOf(stay.day)"
          />
          <div v-else class="hotel-placeholder" aria-hidden="true"><AppIcon name="hotel" size="28" /></div>
          <div class="hotel-copy">
            <strong>{{ hotelNameOf(stay.day) }}</strong>
            <span v-if="hotelLocationOf(stay.day.hotel)" class="hotel-location">{{ hotelLocationOf(stay.day.hotel) }}</span>
            <span v-if="starRatingLabel(stay.day.hotel?.starRating)" class="hotel-star">
              官方星级 {{ starRatingLabel(stay.day.hotel.starRating) }}
            </span>
          </div>

          <RouterLink
            v-if="routeId && stay.day.hotel"
            :to="{ name: 'hotel-detail', params: { routeId, hotelId: stay.day.hotel.id } }"
            class="hotel-link"
            :aria-label="`查看${hotelNameOf(stay.day)}详情`"
          >
            <span>查看详情</span><AppIcon name="chevron-right" :size="14" />
          </RouterLink>
        </div>
        <p v-if="!stay.day.hotel" class="accommodation-note">
          这家酒店目前没有可公开的资料页，住宿仍按行程安排执行。
        </p>
          <p v-if="!hotelNameOf(stay.day)" class="accommodation-empty">指定酒店（酒店名称暂未提供）</p>
        <div v-for="detail in stay.details" :key="detail.day.id" class="stay-details">
          <span v-if="stay.details.length > 1" class="detail-period">{{ nightRange(detail.days) }}</span>
          <p v-if="detail.day.roomType" class="accommodation-line">房型：{{ detail.day.roomType }}</p>
          <p v-if="breakfastLabel(detail.day.breakfastIncluded)" class="accommodation-line">{{ breakfastLabel(detail.day.breakfastIncluded) }}</p>
          <p v-if="detail.day.accommodationNote" class="accommodation-note">{{ detail.day.accommodationNote }}</p>
        </div>
      </article>
    </section>
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
.daily-accommodation { display: grid; grid-template-columns: 16px 28px minmax(0, 1fr); gap: 8px; padding-top: 14px; border-top: 1px solid var(--border-divider); font-size: 11px; color: var(--text-secondary); line-height: 1.7; }
.daily-accommodation p { margin: 5px 0 0; white-space: pre-wrap; }
.daily-hotel-link { color: var(--theme-blue); }
.daily-hotel-link:hover { text-decoration: underline; }
.stay-overview { display: grid; gap: 10px; margin-top: 12px; padding-top: 20px; border-top: 1px solid var(--border-divider); }
.stay-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.stay-heading h4 { margin: 0; color: var(--text-primary); font-size: 13px; font-weight: 600; }
.stay-card { padding: 14px; border: 1px solid var(--border-divider); border-radius: var(--radius-md); background: #fff; overflow-wrap: anywhere; }
.stay-period { display: flex; align-items: center; justify-content: space-between; gap: 8px; margin-bottom: 12px; }
.stay-period strong { color: var(--theme-blue); font-size: 11px; font-weight: 500; }
.stay-period > span { color: var(--text-tertiary); font-size: 10px; }
.stay-details { margin-top: 10px; padding-top: 8px; border-top: 1px solid var(--border-divider); }
.stay-details:empty { display: none; }
.detail-period { color: var(--text-tertiary); font-size: 10px; }
.hotel-card { display: flex; align-items: center; gap: 12px; }
.hotel-link { display: flex; align-items: center; gap: 2px; margin-left: auto; padding: 7px 0 7px 7px; font-size: 10px; flex-shrink: 0; border-radius: 6px; color: var(--theme-blue); background: #fff; }
.hotel-link:focus-visible, .daily-hotel-link:focus-visible { outline: 2px solid var(--theme-blue); outline-offset: 2px; }
.hotel-link:hover { background: var(--theme-blue-tint); }
.hotel-placeholder { display: grid; place-items: center; flex-shrink: 0; width: 64px; height: 64px; border-radius: 8px; background: #edf0f5; color: #8c99ac; }
.hotel-cover { flex-shrink: 0; width: 64px; height: 64px; border-radius: 8px; object-fit: cover; background: #edf0f5; }
.hotel-copy { display: grid; gap: 6px; min-width: 0; }
.hotel-copy span { font-size: 10px; color: var(--text-tertiary); }
.hotel-copy strong { font-size: 13px; font-weight: 500; line-height: 1.5; }
.hotel-location { line-height: 1.6; }
.hotel-star { color: var(--price-orange, #b45309) !important; }
.accommodation-empty { margin: 0; color: var(--text-tertiary); font-size: 11px; }
.accommodation-line { margin: 8px 0 0; color: var(--text-secondary); font-size: 11px; line-height: 1.7; }
.accommodation-note { margin: 8px 0 0; color: var(--text-tertiary); font-size: 11px; line-height: 1.7; white-space: pre-wrap; }
.day-heading:focus-visible, .itinerary-toolbar button:focus-visible { outline: 2px solid var(--theme-blue); outline-offset: -2px; }
@media (max-width: 480px) { .day-heading { padding: 14px 12px; gap: 10px; } .day-body { padding: 0 12px 14px; } }
</style>
