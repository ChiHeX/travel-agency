<script setup>
import { computed } from 'vue'

const props = defineProps({
  name: {
    type: String,
    required: true
  },
  size: {
    type: [Number, String],
    default: 16
  },
  color: {
    type: String,
    default: 'currentColor'
  }
})

const sizePx = computed(() => (typeof props.size === 'number' ? `${props.size}px` : props.size))
</script>

<template>
  <svg
    class="app-icon"
    :width="sizePx"
    :height="sizePx"
    viewBox="0 0 24 24"
    fill="none"
    :stroke="color"
    stroke-width="2"
    stroke-linecap="round"
    stroke-linejoin="round"
  >
    <!-- Brand Logo: 行迹旅行 (Horizon Compass Flight Path) -->
    <template v-if="name === 'brand' || name === 'logo'">
      <circle cx="12" cy="12" r="9" stroke-width="1.8" />
      <path d="M12 3v18" stroke-dasharray="2,2" stroke-width="1.2" />
      <path d="M3 12h18" stroke-dasharray="2,2" stroke-width="1.2" />
      <path d="M16 8L8 12L12 16L16 8Z" fill="currentColor" stroke-width="1.5" />
    </template>

    <!-- Search / 探索 (SF Symbol: magnifyingglass) -->
    <template v-else-if="name === 'search'">
      <circle cx="11" cy="11" r="8" />
      <line x1="21" y1="21" x2="16.65" y2="16.65" />
    </template>

    <!-- Guides / 指南 (SF Symbol: square.grid.2x2) -->
    <template v-else-if="name === 'guides'">
      <rect x="3" y="3" width="7" height="7" rx="1.5" />
      <rect x="14" y="3" width="7" height="7" rx="1.5" />
      <rect x="14" y="14" width="7" height="7" rx="1.5" />
      <rect x="3" y="14" width="7" height="7" rx="1.5" />
    </template>

    <!-- Routes / 路线 (SF Symbol: arrow.triangle.turn.up.right.diamond) -->
    <template v-else-if="name === 'routes'">
      <path d="M4 19L9 14L13 18L20 8" />
      <polyline points="15 8 20 8 20 13" />
    </template>

    <!-- Bell / 消息中心 (SF Symbol: bell) -->
    <template v-else-if="name === 'bell'">
      <path d="M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9" />
      <path d="M13.73 21a2 2 0 0 1-3.46 0" />
    </template>

    <!-- Work / 工作台 (SF Symbol: briefcase) -->
    <template v-else-if="name === 'briefcase' || name === 'work'">
      <rect x="2" y="7" width="20" height="14" rx="2" ry="2" />
      <path d="M16 21V5a2 2 0 0 0-2-2h-4a2 2 0 0 0-2 2v16" />
    </template>

    <!-- Sidebar Toggle (SF Symbol: sidebar.left) -->
    <template v-else-if="name === 'sidebar'">
      <rect x="3" y="3" width="18" height="18" rx="2" ry="2" />
      <line x1="9" y1="3" x2="9" y2="21" />
    </template>

    <!-- Car / 旅游大巴 / 驾车 (SF Symbol: car.fill) -->
    <template v-else-if="name === 'car' || name === 'bus'">
      <path d="M19 17h2c.6 0 1-.4 1-1v-3c0-.9-.7-1.7-1.5-1.9C18.7 10.6 16 10 16 10s-1.3-1.4-2.2-2.3c-.5-.4-1.1-.7-1.8-.7H5c-.6 0-1.1.4-1.4.9l-1.5 3C2.1 11.2 2 11.6 2 12v4c0 .6.4 1 1 1h2" />
      <circle cx="7" cy="17" r="2" />
      <path d="M9 17h6" />
      <circle cx="17" cy="17" r="2" />
    </template>

    <!-- Walk / 徒步 (SF Symbol: figure.walk) -->
    <template v-else-if="name === 'walk'">
      <circle cx="12" cy="4" r="2" />
      <path d="m15 22-3-7-3 3-3-3" />
      <path d="m18 10-4.5 3-3-4-2.5 3" />
    </template>

    <!-- Flight / 飞机 (SF Symbol: airplane) -->
    <template v-else-if="name === 'flight' || name === 'plane'">
      <path d="M17.8 19.2 16 11l3.5-3.5C21 6 21.5 4 21 3.5c-.5-.5-2.5 0-4 1.5L13.5 8.5 5.3 6.7c-.8-.2-1.6.1-2 .7l-.5.8c-.4.6-.2 1.4.4 1.8l5.5 3.8-3.2 3.2-2.5-.5c-.5-.1-1 .1-1.3.5l-.2.3c-.3.4-.2.9.2 1.2l2.3 1.8 1.8 2.3c.3.4.8.5 1.2.2l.3-.2c.4-.3.6-.8.5-1.3l-.5-2.5 3.2-3.2 3.8 5.5c.4.6 1.2.8 1.8.4l.8-.5c.6-.4.9-1.2.7-2z" />
    </template>

    <!-- Restaurant / Meals (SF Symbol: fork.knife) -->
    <template v-else-if="name === 'food' || name === 'meal'">
      <path d="M18 2v20M14 2v20M14 7h4M5 2v10a3 3 0 0 0 3 3v7M9 2v10M5 2c0 2 2 4 4 4" />
    </template>

    <!-- Hotel / Bed (SF Symbol: bed.double) -->
    <template v-else-if="name === 'hotel' || name === 'bed'">
      <path d="M2 4v16M2 8h18a2 2 0 0 1 2 2v10M2 17h20M6 8v9" />
    </template>

    <!-- Location Pin (SF Symbol: mappin.and.ellipse) -->
    <template v-else-if="name === 'pin'">
      <path d="M21 10c0 7-9 13-9 13s-9-6-9-13a9 9 0 0 1 18 0z" />
      <circle cx="12" cy="10" r="3" />
    </template>

    <!-- Circle (SF Symbol: circle) -->
    <template v-else-if="name === 'circle'">
      <circle cx="12" cy="12" r="8" stroke-width="2.5" />
    </template>

    <!-- Drag Handle (SF Symbol: line.3.horizontal) -->
    <template v-else-if="name === 'drag' || name === 'menu'">
      <line x1="3" y1="6" x2="21" y2="6" />
      <line x1="3" y1="12" x2="21" y2="12" />
      <line x1="3" y1="18" x2="21" y2="18" />
    </template>

    <!-- Close (SF Symbol: xmark) -->
    <template v-else-if="name === 'close' || name === 'x'">
      <line x1="18" y1="6" x2="6" y2="18" />
      <line x1="6" y1="6" x2="18" y2="18" />
    </template>

    <!-- Share (SF Symbol: square.and.arrow.up) -->
    <template v-else-if="name === 'share'">
      <path d="M4 12v8a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-8" />
      <polyline points="16 6 12 2 8 6" />
      <line x1="12" y1="2" x2="12" y2="15" />
    </template>

    <!-- Refresh (SF Symbol: arrow.clockwise) -->
    <template v-else-if="name === 'refresh' || name === 'reset'">
      <path d="M21.5 2v6h-6M21.34 15.57a10 10 0 1 1-.57-8.38l5.67-5.67" />
    </template>

    <!-- Star (SF Symbol: star.fill) -->
    <template v-else-if="name === 'star'">
      <polygon
        points="12 2 15.09 8.26 22 9.27 17 14.14 18.18 21.02 12 17.77 5.82 21.02 7 14.14 2 9.27 8.91 8.26 12 2"
        fill="currentColor"
        stroke="none"
      />
    </template>

    <!-- Heart (SF Symbol: heart.fill) -->
    <template v-else-if="name === 'heart-filled'">
      <path
        d="M19 14c1.49-1.46 3-3.21 3-5.5A5.5 5.5 0 0 0 16.5 3c-1.76 0-3 .5-4.5 2-1.5-1.5-2.74-2-4.5-2A5.5 5.5 0 0 0 2 8.5c0 2.3 1.5 4.05 3 5.5l7 7Z"
        fill="currentColor"
        stroke="none"
      />
    </template>

    <!-- Heart Outline (SF Symbol: heart) -->
    <template v-else-if="name === 'heart'">
      <path d="M19 14c1.49-1.46 3-3.21 3-5.5A5.5 5.5 0 0 0 16.5 3c-1.76 0-3 .5-4.5 2-1.5-1.5-2.74-2-4.5-2A5.5 5.5 0 0 0 2 8.5c0 2.3 1.5 4.05 3 5.5l7 7Z" />
    </template>

    <!-- Compass (SF Symbol: safari) -->
    <template v-else-if="name === 'compass'">
      <circle cx="12" cy="12" r="10" />
      <polygon points="16.24 7.76 14.12 14.12 7.76 16.24 9.88 9.88 16.24 7.76" fill="currentColor" />
    </template>

    <!-- Logout / Power (SF Symbol: power) -->
    <template v-else-if="name === 'logout' || name === 'power'">
      <path d="M18.36 6.64a9 9 0 1 1-12.73 0" />
      <line x1="12" y1="2" x2="12" y2="12" />
    </template>

    <!-- Chevron Left (SF Symbol: chevron.left) -->
    <template v-else-if="name === 'chevron-left'">
      <polyline points="15 18 9 12 15 6" />
    </template>

    <!-- Chevron Right (SF Symbol: chevron.right) -->
    <template v-else-if="name === 'chevron-right'">
      <polyline points="9 18 15 12 9 6" />
    </template>

    <!-- Filter / Sliders (SF Symbol: slider.horizontal.3) -->
    <template v-else-if="name === 'filter'">
      <line x1="4" y1="21" x2="4" y2="14" />
      <line x1="4" y1="10" x2="4" y2="3" />
      <line x1="12" y1="21" x2="12" y2="12" />
      <line x1="12" y1="8" x2="12" y2="3" />
      <line x1="20" y1="21" x2="20" y2="16" />
      <line x1="20" y1="12" x2="20" y2="3" />
      <line x1="1" y1="14" x2="7" y2="14" />
      <line x1="9" y1="8" x2="15" y2="8" />
      <line x1="17" y1="16" x2="23" y2="16" />
    </template>
  </svg>
</template>

<style scoped>
.app-icon {
  display: inline-block;
  vertical-align: middle;
  flex-shrink: 0;
}
</style>
