<script setup>
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { routeApi } from '@/api/modules'
import AppIcon from '@/components/AppIcon.vue'
import MapPreview from '@/components/MapPreview.vue'
import StickyDetailBar from '@/components/StickyDetailBar.vue'
import RequestState from '@/components/RequestState.vue'
import { checkInLabel, checkOutLabel, facilityLabel, starRatingLabel } from '@/utils/hotel'

/**
 * 用户端酒店详情（契约 `GET /routes/{routeId}/hotels/{hotelId}` → `PublicHotelDetail`）。
 *
 * <p>只渲染契约真实返回的字段：名称、城市、地址、官方星级、简介、设施、入住/退房时间、
 * 图片、坐标与资料来源。<b>没有</b>评分、评价数量、销量、房价与库存这类字段，
 * 页面因此一概不显示 —— `docs/DEVELOPMENT_GUIDE.md` §4 禁止为了页面"看起来完整"
 * 生成虚假的评分或统计数据。</p>
 *
 * <p>该端点对「线路未发布 / 酒店未安排在这条线路的行程里 / 酒店已停用」统一返回 404，
 * 且刻意不区分原因。因此这里把失败处理成一个明确的用户文案而不是原始错误：
 * 用户不需要看到一个后端错误码，也不需要知道后台酒店的启用状态。</p>
 */
const route = useRoute()
const loading = ref(false)
const error = ref('')
const hotel = ref(null)

/** 失败时拿不到名称（请求本身没有返回任何字段），此时用中性标题，不编造也不去多打一次线路详情接口。 */
const hotelName = computed(() => hotel.value?.name || '酒店详情')
const backTo = computed(() => ({ name: 'route-detail', params: { id: route.params.routeId } }))
/** 图片列表（服务端已按 sortOrder 升序返回）；没有图片时是空数组，不是错误。 */
const gallery = computed(() => (hotel.value?.images || []).filter((image) => image?.url))
/** 封面：`coverUrl` 为空时回退到 `images[0]`（服务端已按 sortOrder 排序），仍为空则显示占位块。 */
const coverUrl = computed(() => hotel.value?.coverUrl || gallery.value[0]?.url || '')
/** 只保留契约枚举认识的设施：未知取值返回 null，跳过而不是把枚举原文印在页面上。 */
const facilities = computed(() => (hotel.value?.facilities || [])
  .map((value) => ({ value, label: facilityLabel(value) }))
  .filter((item) => item.label !== null))
const starLabel = computed(() => starRatingLabel(hotel.value?.starRating))
const checkIn = computed(() => checkInLabel(hotel.value?.checkInTime))
const checkOut = computed(() => checkOutLabel(hotel.value?.checkOutTime))
/** 坐标只在经纬度都是数字时才落点，否则位置数据不可靠（契约明确要求前端不得自行编造位置）。 */
const hasCoordinates = computed(
  () => typeof hotel.value?.longitude === 'number' && typeof hotel.value?.latitude === 'number'
)
const locationPoints = computed(() => (hasCoordinates.value
  ? [{ name: hotel.value.name, longitude: hotel.value.longitude, latitude: hotel.value.latitude }]
  : []))

let requestId = 0
async function load() {
  const current = ++requestId
  loading.value = true
  error.value = ''
  hotel.value = null
  try {
    const result = await routeApi.hotel(route.params.routeId, route.params.hotelId)
    if (current !== requestId) return
    hotel.value = result || null
    if (!hotel.value) error.value = '该酒店资料暂不可查看'
  } catch {
    // 404（线路未发布 / 酒店未安排在这条线路 / 酒店已停用）与网络失败对用户是同一件事：
    // 现在看不到这份资料。不回显后端 message，也不区分原因。
    if (current === requestId) error.value = '该酒店资料暂不可查看'
  } finally {
    if (current === requestId) loading.value = false
  }
}

async function share() {
  try {
    if (navigator.share) await navigator.share({ title: hotelName.value, url: window.location.href })
    else {
      await navigator.clipboard.writeText(window.location.href)
      ElMessage.success('酒店链接已复制')
    }
  } catch (cause) { if (cause.name !== 'AbortError') ElMessage.warning('分享失败，请复制浏览器地址栏中的链接') }
}
watch(() => [route.params.routeId, route.params.hotelId], load, { immediate: true })
</script>

<template>
  <div class="hotel-detail">
    <StickyDetailBar :title="hotelName" :fallback-to="backTo" @share="share" />
    <main>
      <RequestState :loading="loading" :error="error" @retry="load">
        <template v-if="hotel">
          <header class="hotel-header">
            <div class="hotel-kicker"><AppIcon name="hotel" :size="14" />{{ hotel.city }} · 行程住宿</div>
            <h1>{{ hotel.name }}</h1>
            <p v-if="hotel.address">{{ hotel.address }}</p>
          </header>

          <img v-if="coverUrl" class="hotel-cover-media" :src="coverUrl" :alt="hotel.name" />
          <div v-else class="hotel-cover"><AppIcon name="hotel" :size="48" /><span>酒店图片暂未提供</span></div>

          <section>
            <h2>酒店资料</h2>
            <div class="hotel-info-card">
              <div class="hotel-info-row"><span>城市</span><strong>{{ hotel.city }}</strong></div>
              <div v-if="hotel.address" class="hotel-info-row"><span>地址</span><strong>{{ hotel.address }}</strong></div>
              <!-- 官方星级：没有可靠依据时契约返回 null，此时整行不显示（不做任何推测） -->
              <div v-if="starLabel" class="hotel-info-row"><span>星级</span><strong>{{ starLabel }}</strong></div>
              <div v-if="checkIn" class="hotel-info-row"><span>入住</span><strong>{{ checkIn }}</strong></div>
              <div v-if="checkOut" class="hotel-info-row"><span>退房</span><strong>{{ checkOut }}</strong></div>
              <div v-if="hotel.dataSource" class="hotel-info-row"><span>资料来源</span><strong>{{ hotel.dataSource }}</strong></div>
            </div>
          </section>

          <section>
            <h2>关于酒店</h2>
            <div class="hotel-info-card">
              <p v-if="hotel.intro" class="hotel-intro">{{ hotel.intro }}</p>
              <p v-else class="hotel-empty">酒店暂未提供公开简介。</p>
            </div>
          </section>

          <section>
            <h2>酒店设施</h2>
            <div class="hotel-info-card">
              <div v-if="facilities.length" class="hotel-facilities">
                <span v-for="facility in facilities" :key="facility.value" class="hotel-facility">{{ facility.label }}</span>
              </div>
              <p v-else class="hotel-empty">酒店暂未提供设施资料。</p>
            </div>
          </section>

          <!-- 坐标都非空才落点；契约要求前端不得自行编造位置 -->
          <section v-if="hasCoordinates">
            <h2>酒店位置</h2>
            <div class="hotel-map">
              <MapPreview :places="locationPoints" />
            </div>
          </section>

          <section v-if="gallery.length">
            <h2>酒店图片</h2>
            <div class="hotel-gallery">
              <img
                v-for="image in gallery"
                :key="image.url"
                :src="image.url"
                :alt="image.alt || hotel.name"
                loading="lazy"
              />
            </div>
          </section>

          <RouterLink :to="backTo" class="secondary-button hotel-back">返回线路详情</RouterLink>
        </template>
      </RequestState>

      <!--
        失败不等于"出错"：这个端点对未发布线路、未安排该酒店的线路与已停用酒店统一回 404，
        对用户而言就是"现在看不到这份资料"。这里给出可读的下一步，并保留返回线路详情的入口。
      -->
      <p v-if="!loading && error" class="hotel-error-hint">
        这条线路的行程里可能没有这家酒店，或该酒店已停止对外展示。你可以返回线路详情查看最新的每日行程安排。
      </p>
      <RouterLink v-if="!loading && !hotel" :to="backTo" class="secondary-button hotel-back">返回线路详情</RouterLink>
    </main>
  </div>
</template>

<style scoped>
.hotel-detail { color: var(--text-primary); }
.hotel-detail main { padding: 12px 20px 32px; }
.hotel-kicker { display: flex; align-items: center; gap: 6px; color: var(--theme-blue); font-size: 11px; }
.hotel-header h1 { margin: 12px 0 8px; font-size: 26px; font-weight: 600; line-height: 1.3; letter-spacing: -.5px; overflow-wrap: anywhere; }
.hotel-header > p { margin: 0 0 24px; color: var(--text-secondary); font-size: 12px; line-height: 1.6; }
.hotel-cover { display: grid; place-content: center; justify-items: center; gap: 12px; min-height: 180px; border-radius: var(--radius-lg); background: #edf0f5; color: #8c99ac; }
.hotel-cover span { font-size: 11px; }
.hotel-cover-media { display: block; width: 100%; min-height: 180px; max-height: 280px; border-radius: var(--radius-lg); background: #edf0f5; object-fit: cover; }
.hotel-detail section { margin-top: 28px; }
.hotel-detail h2 { font-size: 15px; font-weight: 600; margin: 0 0 12px; }
.hotel-info-card { padding: 18px; border: 1px solid var(--border-divider); border-radius: var(--radius-md); background: #fff; }
.hotel-info-row { display: grid; grid-template-columns: 64px minmax(0, 1fr); gap: 12px; font-size: 12px; line-height: 1.7; }
.hotel-info-row + .hotel-info-row { margin-top: 12px; }
.hotel-info-row span, .hotel-empty { color: var(--text-secondary); }
.hotel-info-row strong { font-weight: 500; overflow-wrap: anywhere; }
.hotel-empty { margin: 0; font-size: 12px; line-height: 1.8; }
.hotel-intro { margin: 0; font-size: 12px; line-height: 1.8; color: var(--text-secondary); white-space: pre-wrap; }
.hotel-facilities { display: flex; flex-wrap: wrap; gap: 8px; }
.hotel-facility { padding: 5px 10px; border-radius: 999px; background: var(--theme-blue-tint); color: var(--theme-blue); font-size: 11px; }
.hotel-map { position: relative; height: 240px; border-radius: var(--radius-md); overflow: hidden; background: #edf0f5; }
/* MapPreview 原本是全屏地图面板，嵌进详情页时恢复成容器内定位（它针对 ≤900px 有下移规则） */
.hotel-map :deep(.map-preview-card) { inset: 0; }
.hotel-gallery { display: grid; grid-template-columns: repeat(auto-fill, minmax(140px, 1fr)); gap: 10px; }
.hotel-gallery img { width: 100%; height: 110px; border-radius: var(--radius-sm); background: #edf0f5; object-fit: cover; }
.hotel-error-hint { margin: 12px 0 0; color: var(--text-secondary); font-size: 12px; line-height: 1.8; }
.hotel-back { display: inline-block; margin-top: 28px; }
</style>
