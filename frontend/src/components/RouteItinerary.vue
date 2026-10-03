<script setup>
import { computed, ref, watch } from 'vue'
import AppIcon from '@/components/AppIcon.vue'
import {
  accommodationLabel,
  accommodationSummary,
  accommodationTypeOf,
  breakfastLabel,
  starRatingLabel
} from '@/utils/hotel'

/**
 * 用户端每日行程（含"当晚住宿"）。
 *
 * <p>住宿一节完全按契约 {@code ItineraryDay} 的新字段渲染，**不推断**契约没有的数据：</p>
 * <ul>
 *   <li>{@code accommodationType} 声明当天到底怎么安排住宿。{@code hotelId} 为空
 *       <b>不等于</b>"不含住宿"：那可能是"只确定了住宿标准"或"还没确认"，
 *       因此这里一律先看 {@code accommodationType}，缺失时才按服务端迁移口径兜底
 *       （见 {@code @/utils/hotel} 的 accommodationTypeOf）；</li>
 *   <li>详情入口只在 {@code day.hotel} 非空时给出：酒店被停用或已删除时该摘要为 {@code null}，
 *       而 {@code GET /routes/{routeId}/hotels/{hotelId}} 对这种酒店一律返回 404，
 *       无条件加链接等于给用户一个必然报错的入口。行程本身仍安排了这家酒店，
 *       所以名称照常显示，只是补一句"暂无公开详情页"；</li>
 *   <li>{@code starRating} 是<b>官方星级</b>，只在它是数字时显示；本项目没有酒店评价体系，
 *       不得拿网站评分或"几钻"顶替；</li>
 *   <li>{@code breakfastIncluded} 是三态：{@code false} 要显示成"不含早餐"，
 *       {@code null} 表示尚未说明、整项不显示（{@code false} 与"未说明"不能混为一谈）。</li>
 * </ul>
 */
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
/** 当天安排的酒店名称：`hotelName` 是行程自身的事实，酒店摘要缺失时仍要显示出来。 */
function hotelNameOf(day) {
  return day.hotelName || day.hotel?.name || ''
}
/** 酒店卡片副标题：只用摘要里真的有的字段，城市与地址缺一个就只显示另一个。 */
function hotelLocationOf(hotel) {
  return [hotel?.city, hotel?.address].filter((value) => typeof value === 'string' && value.trim() !== '').join(' · ')
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
          <span>{{ day.items?.length ? `${day.items.length} 项行程` : '当日安排' }} · {{ accommodationSummary(day) }}</span>
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

          <!-- 指定酒店：行程确实安排了这家酒店 -->
          <template v-if="accommodationTypeOf(day) === 'HOTEL'">
            <template v-if="hotelNameOf(day)">
              <div class="hotel-card">
                <img
                  v-if="day.hotel?.coverUrl"
                  class="hotel-cover"
                  :src="day.hotel.coverUrl"
                  :alt="hotelNameOf(day)"
                />
                <div v-else class="hotel-placeholder" aria-hidden="true"><AppIcon name="hotel" size="28" /></div>
                <div class="hotel-copy">
                  <span>行程安排酒店</span>
                  <strong>{{ hotelNameOf(day) }}</strong>
                  <span v-if="hotelLocationOf(day.hotel)" class="hotel-location">{{ hotelLocationOf(day.hotel) }}</span>
                  <span v-if="starRatingLabel(day.hotel?.starRating)" class="hotel-star">
                    官方星级 {{ starRatingLabel(day.hotel.starRating) }}
                  </span>
                </div>
                <!-- 只有拿到酒店摘要时才给详情入口：摘要为 null 时详情接口一律 404 -->
                <RouterLink
                  v-if="routeId && day.hotel"
                  :to="{ name: 'hotel-detail', params: { routeId, hotelId: day.hotel.id } }"
                  class="hotel-link"
                  :aria-label="`查看${hotelNameOf(day)}详情`"
                >
                  <AppIcon name="chevron-right" :size="16" />
                </RouterLink>
              </div>
              <p v-if="!day.hotel" class="accommodation-note">
                这家酒店目前没有可公开的资料页（可能已停止合作或资料未公开），当天行程仍按原安排执行。
              </p>
              <p v-if="day.roomType" class="accommodation-line">房型：{{ day.roomType }}</p>
              <p v-if="breakfastLabel(day.breakfastIncluded)" class="accommodation-line">
                {{ breakfastLabel(day.breakfastIncluded) }}
              </p>
              <p v-if="day.accommodationNote" class="accommodation-note">{{ day.accommodationNote }}</p>
            </template>
            <p v-else class="accommodation-empty">{{ accommodationLabel('HOTEL') }}（酒店名称暂未提供）</p>
          </template>

          <!-- 只确定住宿标准：不指定酒店，因此没有酒店详情入口 -->
          <template v-else-if="accommodationTypeOf(day) === 'STANDARD'">
            <p class="accommodation-primary">{{ accommodationLabel('STANDARD') }}</p>
            <p v-if="day.accommodationStandard" class="accommodation-line">住宿标准：{{ day.accommodationStandard }}</p>
            <p v-if="day.roomType" class="accommodation-line">房型：{{ day.roomType }}</p>
            <p v-if="breakfastLabel(day.breakfastIncluded)" class="accommodation-line">
              {{ breakfastLabel(day.breakfastIncluded) }}
            </p>
            <p v-if="day.accommodationNote" class="accommodation-note">{{ day.accommodationNote }}</p>
          </template>

          <!-- 当天不含住宿 -->
          <template v-else-if="accommodationTypeOf(day) === 'NONE'">
            <p class="accommodation-primary">{{ accommodationLabel('NONE') }}</p>
            <p v-if="day.accommodationNote" class="accommodation-note">{{ day.accommodationNote }}</p>
          </template>

          <!-- 住宿待确认 -->
          <template v-else>
            <p class="accommodation-primary">{{ accommodationLabel('PENDING') }}</p>
            <p v-if="day.accommodationNote" class="accommodation-note">{{ day.accommodationNote }}</p>
          </template>
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
.hotel-cover { flex-shrink: 0; width: 64px; height: 64px; border-radius: 8px; object-fit: cover; background: #edf0f5; }
.hotel-copy { display: grid; gap: 6px; min-width: 0; }
.hotel-copy span { font-size: 10px; color: var(--text-tertiary); }
.hotel-copy strong { font-size: 13px; font-weight: 500; line-height: 1.5; }
.hotel-location { line-height: 1.6; }
.hotel-star { color: var(--price-orange, #b45309) !important; }
.accommodation-empty, .accommodation-primary { margin: 0; color: var(--text-tertiary); font-size: 11px; }
.accommodation-primary { color: var(--text-secondary); font-weight: 500; }
.accommodation-line { margin: 8px 0 0; color: var(--text-secondary); font-size: 11px; line-height: 1.7; }
.accommodation-note { margin: 8px 0 0; color: var(--text-tertiary); font-size: 11px; line-height: 1.7; }
.day-heading:focus-visible, .itinerary-toolbar button:focus-visible { outline: 2px solid var(--theme-blue); outline-offset: -2px; }
@media (max-width: 480px) { .day-heading { padding: 14px 12px; gap: 10px; } .day-body { padding: 0 12px 14px; } }
</style>
