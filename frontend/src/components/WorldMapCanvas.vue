<script setup>
import { onMounted, onUnmounted, ref } from 'vue'
import AppIcon from '@/components/AppIcon.vue'

const props = defineProps({
  pins: {
    type: Array,
    default: () => []
  }
})

const emit = defineEmits(['pin-click'])

const WORLD_WIDTH = 2560
const WORLD_HEIGHT = 1400

const panX = ref(-800) // Initial position focusing around Europe / Asia
const panY = ref(-260)
const scale = ref(0.9)
const isDragging = ref(false)
const dragStart = { x: 0, y: 0, panX: 0, panY: 0 }

// Repeating tiles array to guarantee infinite horizontal wrap: [-1, 0, 1]
const tileOffsets = [-1, 0, 1]

function onMouseDown(e) {
  if (e.button !== 0) return
  isDragging.value = true
  dragStart.x = e.clientX
  dragStart.y = e.clientY
  dragStart.panX = panX.value
  dragStart.panY = panY.value
}

function onMouseMove(e) {
  if (!isDragging.value) return
  let currentPanX = dragStart.panX + (e.clientX - dragStart.x)
  const currentPanY = dragStart.panY + (e.clientY - dragStart.y)

  // Infinite horizontal wrapping around the globe
  const scaledW = WORLD_WIDTH * scale.value
  while (currentPanX > 0) {
    currentPanX -= scaledW
    dragStart.panX -= scaledW
  }
  while (currentPanX < -scaledW) {
    currentPanX += scaledW
    dragStart.panX += scaledW
  }
  panX.value = currentPanX

  // Vertical bounds clamping (staying within globe latitudes, ocean pads the rest)
  const viewH = window.innerHeight
  const scaledH = WORLD_HEIGHT * scale.value
  const minPanY = Math.min(0, viewH - scaledH - 80)
  const maxPanY = 80
  panY.value = Math.max(Math.min(currentPanY, maxPanY), minPanY)
}

function onMouseUp() {
  isDragging.value = false
}

function applyZoomAt(anchorX, anchorY, targetScale) {
  const oldScale = scale.value
  const clampedScale = Math.min(Math.max(targetScale, 0.55), 3.0)
  if (Math.abs(clampedScale - oldScale) < 0.0001) return

  const ratio = clampedScale / oldScale

  // Mathematical formula: keep (anchorX, anchorY) at the identical world coordinate
  let newPanX = anchorX - (anchorX - panX.value) * ratio
  let newPanY = anchorY - (anchorY - panY.value) * ratio

  // Infinite horizontal wrapping
  const scaledW = WORLD_WIDTH * clampedScale
  while (newPanX > 0) {
    newPanX -= scaledW
  }
  while (newPanX < -scaledW) {
    newPanX += scaledW
  }

  // Vertical bounds clamping
  const viewH = window.innerHeight
  const scaledH = WORLD_HEIGHT * clampedScale
  const minPanY = Math.min(0, viewH - scaledH - 80)
  const maxPanY = 80
  newPanY = Math.max(Math.min(newPanY, maxPanY), minPanY)

  scale.value = Number(clampedScale.toFixed(4))
  panX.value = Math.round(newPanX)
  panY.value = Math.round(newPanY)
}

function onWheel(e) {
  const zoomFactor = e.deltaY < 0 ? 1.12 : (1 / 1.12)
  const targetScale = scale.value * zoomFactor

  // Anchor zoom exactly at current mouse position
  applyZoomAt(e.clientX, e.clientY, targetScale)
}

function zoomIn() {
  const centerX = window.innerWidth / 2
  const centerY = window.innerHeight / 2
  applyZoomAt(centerX, centerY, scale.value * 1.25)
}

function zoomOut() {
  const centerX = window.innerWidth / 2
  const centerY = window.innerHeight / 2
  applyZoomAt(centerX, centerY, scale.value / 1.25)
}

function resetView() {
  panX.value = -800
  panY.value = -260
  scale.value = 0.9
}

onMounted(() => {
  window.addEventListener('mouseup', onMouseUp)
})

onUnmounted(() => {
  window.removeEventListener('mouseup', onMouseUp)
})
</script>

<template>
  <div
    class="world-map-viewport"
    :class="{ dragging: isDragging }"
    @mousedown="onMouseDown"
    @mousemove="onMouseMove"
    @wheel.prevent="onWheel"
  >
    <!-- Transformed Infinite World Pan & Zoom Layer -->
    <div
      class="world-pan-layer"
      :style="{
        transform: `translate3d(${panX}px, ${panY}px, 0px) scale(${scale})`,
        transformOrigin: '0 0'
      }"
    >
      <svg
        class="world-vector-svg"
        :width="WORLD_WIDTH * 3"
        :height="WORLD_HEIGHT"
        :viewBox="`-${WORLD_WIDTH} 0 ${WORLD_WIDTH * 3} ${WORLD_HEIGHT}`"
      >
        <defs>
          <!-- Subtle ocean latitude/longitude grid pattern -->
          <pattern id="globe-grid" width="160" height="140" patternUnits="userSpaceOnUse">
            <path d="M 160 0 L 0 0 0 140" fill="none" stroke="#ffffff" stroke-width="0.8" stroke-dasharray="4,6" opacity="0.18" />
          </pattern>

          <!-- Reusable World Continent Model -->
          <g id="single-world-globe">
            <!-- Ocean base rectangle -->
            <rect x="0" y="0" width="2560" height="1400" fill="#9ec9eb" />
            <rect x="0" y="0" width="2560" height="1400" fill="url(#globe-grid)" />

            <!-- Equator & Tropic Lines -->
            <line x1="0" y1="700" x2="2560" y2="700" stroke="#ffffff" stroke-width="1.2" stroke-dasharray="8,6" opacity="0.4" />
            <line x1="0" y1="460" x2="2560" y2="460" stroke="#ffffff" stroke-width="0.8" stroke-dasharray="6,6" opacity="0.25" />
            <line x1="0" y1="940" x2="2560" y2="940" stroke="#ffffff" stroke-width="0.8" stroke-dasharray="6,6" opacity="0.25" />

            <!-- Longitude Meridians -->
            <line x1="1040" y1="0" x2="1040" y2="1400" stroke="#ffffff" stroke-width="1.2" stroke-dasharray="8,6" opacity="0.4" />
            <line x1="400" y1="0" x2="400" y2="1400" stroke="#ffffff" stroke-width="0.8" stroke-dasharray="6,6" opacity="0.2" />
            <line x1="1720" y1="0" x2="1720" y2="1400" stroke="#ffffff" stroke-width="0.8" stroke-dasharray="6,6" opacity="0.2" />

            <!-- ==========================================================
                 CONTINENTS (Pastel Sage Green Land #ddeec8 with #c6deb0 outline)
                 ========================================================== -->

            <!-- 1. NORTH AMERICA (Alaska, Canada, USA, Mexico, Central America) -->
            <path
              d="M 180,240
                 C 220,180 320,190 380,220
                 C 440,240 500,210 560,230
                 C 640,240 700,280 720,340
                 C 740,400 700,440 680,480
                 C 670,520 680,560 690,620
                 C 700,660 670,720 640,730
                 C 610,740 580,700 560,680
                 C 530,660 500,700 480,740
                 C 460,780 440,820 480,840
                 C 440,840 420,800 400,760
                 C 380,720 370,660 380,600
                 C 390,540 370,480 340,440
                 C 300,400 240,380 200,340
                 C 160,300 150,260 180,240 Z"
              fill="#ddeec8"
              stroke="#c6deb0"
              stroke-width="2"
            />

            <!-- Greenland -->
            <path
              d="M 780,180
                 C 820,120 900,110 930,150
                 C 960,190 940,260 920,320
                 C 900,360 850,370 820,340
                 C 780,300 760,220 780,180 Z"
              fill="#eef6e6"
              stroke="#c6deb0"
              stroke-width="1.8"
            />

            <!-- Caribbean Islands -->
            <ellipse cx="640" cy="760" rx="28" ry="8" fill="#ddeec8" />
            <ellipse cx="680" cy="780" rx="16" ry="6" fill="#ddeec8" />

            <!-- 2. SOUTH AMERICA (Brazil, Andes, Amazon, Argentina, Chile) -->
            <path
              d="M 540,840
                 C 580,820 660,830 720,850
                 C 780,870 840,920 860,980
                 C 880,1040 840,1120 800,1160
                 C 760,1200 720,1280 690,1340
                 C 670,1360 660,1340 650,1300
                 C 640,1240 640,1160 620,1100
                 C 600,1040 570,980 550,920
                 C 530,870 510,850 540,840 Z"
              fill="#ddeec8"
              stroke="#c6deb0"
              stroke-width="2"
            />

            <!-- 3. EUROPE (British Isles, Scandinavia, Western & Southern Europe) -->
            <!-- British Isles -->
            <path
              d="M 1010,400
                 C 1030,370 1050,380 1060,420
                 C 1070,450 1050,480 1030,490
                 C 1010,490 1000,450 1010,400 Z"
              fill="#ddeec8"
              stroke="#c6deb0"
              stroke-width="1.5"
            />
            <path
              d="M 980,420
                 C 995,410 1000,440 995,460
                 C 985,470 975,450 980,420 Z"
              fill="#ddeec8"
              stroke="#c6deb0"
              stroke-width="1.2"
            />

            <!-- Scandinavia -->
            <path
              d="M 1120,400
                 C 1130,320 1150,220 1200,210
                 C 1240,210 1250,260 1230,340
                 C 1220,380 1190,440 1150,440
                 C 1130,440 1115,430 1120,400 Z"
              fill="#ddeec8"
              stroke="#c6deb0"
              stroke-width="1.8"
            />

            <!-- Continental Europe (France, Spain, Germany, Italy, Balkans) -->
            <path
              d="M 1040,540
                 C 1060,480 1120,460 1160,470
                 C 1200,480 1240,490 1260,530
                 C 1280,560 1240,600 1210,610
                 C 1190,620 1180,580 1170,550
                 C 1160,530 1130,540 1100,560
                 C 1070,580 1040,620 1010,610
                 C 990,600 1020,560 1040,540 Z"
              fill="#ddeec8"
              stroke="#c6deb0"
              stroke-width="2"
            />

            <!-- 4. AFRICA (Sahara, West Africa, Central, East Africa, South Africa) -->
            <path
              d="M 1020,630
                 C 1100,620 1220,630 1280,660
                 C 1340,690 1370,740 1410,800
                 C 1440,850 1400,940 1380,1020
                 C 1360,1100 1330,1180 1290,1250
                 C 1260,1290 1220,1290 1200,1240
                 C 1170,1170 1160,1080 1140,1000
                 C 1120,950 1080,910 1030,900
                 C 970,890 940,840 950,780
                 C 960,720 980,650 1020,630 Z"
              fill="#ddeec8"
              stroke="#c6deb0"
              stroke-width="2"
            />

            <!-- Madagascar -->
            <path
              d="M 1380,1040
                 C 1400,1020 1420,1060 1410,1140
                 C 1400,1170 1380,1160 1370,1120
                 C 1360,1080 1370,1050 1380,1040 Z"
              fill="#ddeec8"
              stroke="#c6deb0"
              stroke-width="1.5"
            />

            <!-- 5. ASIA (Russia, Siberia, Middle East, India, China, Japan, SE Asia) -->
            <!-- Northern Eurasia (Russia / Siberia / Central Asia) -->
            <path
              d="M 1260,470
                 C 1320,380 1400,260 1560,230
                 C 1720,210 1900,220 2060,250
                 C 2160,270 2200,340 2160,390
                 C 2120,430 2040,420 1980,450
                 C 1920,470 1860,490 1780,470
                 C 1680,450 1580,480 1480,510
                 C 1380,530 1300,520 1260,470 Z"
              fill="#ddeec8"
              stroke="#c6deb0"
              stroke-width="2"
            />

            <!-- Arabian Peninsula -->
            <path
              d="M 1280,670
                 C 1340,650 1400,680 1420,730
                 C 1440,780 1410,830 1370,840
                 C 1330,850 1290,790 1270,740
                 C 1260,700 1270,680 1280,670 Z"
              fill="#ddeec8"
              stroke="#c6deb0"
              stroke-width="1.8"
            />

            <!-- Indian Subcontinent -->
            <path
              d="M 1460,650
                 C 1520,630 1580,650 1620,700
                 C 1650,750 1620,830 1580,880
                 C 1550,910 1520,890 1500,840
                 C 1470,780 1440,720 1460,650 Z"
              fill="#ddeec8"
              stroke="#c6deb0"
              stroke-width="2"
            />
            <circle cx="1570" cy="920" r="10" fill="#ddeec8" stroke="#c6deb0" />

            <!-- China & East Asia -->
            <path
              d="M 1580,510
                 C 1680,480 1780,480 1860,510
                 C 1920,530 1960,580 1940,650
                 C 1920,720 1880,770 1820,780
                 C 1760,790 1700,750 1660,720
                 C 1610,680 1570,600 1580,510 Z"
              fill="#ddeec8"
              stroke="#c6deb0"
              stroke-width="2"
            />

            <!-- Korean Peninsula -->
            <path
              d="M 1880,530
                 C 1900,510 1920,540 1915,590
                 C 1905,610 1885,600 1880,570
                 C 1875,550 1875,540 1880,530 Z"
              fill="#ddeec8"
              stroke="#c6deb0"
              stroke-width="1.5"
            />

            <!-- Japanese Archipelago -->
            <path
              d="M 1940,510
                 C 1970,480 2010,470 2030,520
                 C 2040,560 2010,610 1980,630
                 C 1960,630 1940,590 1950,550 Z"
              fill="#ddeec8"
              stroke="#c6deb0"
              stroke-width="1.8"
            />

            <!-- Southeast Asia Mainland (Thailand, Vietnam, Malaysia) -->
            <path
              d="M 1680,720
                 C 1740,710 1790,750 1780,820
                 C 1770,870 1740,910 1735,950
                 C 1720,950 1715,900 1710,850
                 C 1700,800 1670,760 1680,720 Z"
              fill="#ddeec8"
              stroke="#c6deb0"
              stroke-width="1.8"
            />

            <!-- Indonesia & Philippines Archipelagos -->
            <ellipse cx="1800" cy="980" rx="65" ry="14" fill="#ddeec8" stroke="#c6deb0" />
            <ellipse cx="1940" cy="990" rx="55" ry="16" fill="#ddeec8" stroke="#c6deb0" />
            <ellipse cx="2060" cy="970" rx="45" ry="18" fill="#ddeec8" stroke="#c6deb0" />
            <ellipse cx="1860" cy="850" rx="20" ry="40" fill="#ddeec8" stroke="#c6deb0" />

            <!-- 6. OCEANIA (Australia, New Zealand, Papua) -->
            <!-- Papua New Guinea -->
            <ellipse cx="2080" cy="980" rx="60" ry="18" fill="#ddeec8" stroke="#c6deb0" />

            <!-- Australia -->
            <path
              d="M 1940,1060
                 C 2020,1020 2160,1030 2240,1080
                 C 2290,1120 2280,1220 2230,1260
                 C 2180,1290 2080,1280 2010,1260
                 C 1950,1240 1920,1170 1920,1120
                 C 1920,1080 1930,1070 1940,1060 Z"
              fill="#ddeec8"
              stroke="#c6deb0"
              stroke-width="2"
            />
            <circle cx="2180" cy="1320" r="14" fill="#ddeec8" stroke="#c6deb0" />

            <!-- New Zealand -->
            <path
              d="M 2330,1200
                 C 2350,1180 2370,1210 2360,1250
                 C 2350,1270 2330,1260 2330,1200 Z"
              fill="#ddeec8"
              stroke="#c6deb0"
              stroke-width="1.5"
            />
            <path
              d="M 2300,1270
                 C 2320,1250 2340,1280 2320,1330
                 C 2300,1340 2290,1310 2300,1270 Z"
              fill="#ddeec8"
              stroke="#c6deb0"
              stroke-width="1.5"
            />

            <!-- 7. ANTARCTICA (Spanning the bottom of the globe) -->
            <path
              d="M 0,1340
                 C 300,1330 600,1350 900,1330
                 C 1200,1310 1500,1330 1800,1320
                 C 2100,1340 2400,1320 2560,1340
                 L 2560,1400 L 0,1400 Z"
              fill="#f2f7ec"
              stroke="#c6deb0"
              stroke-width="2"
            />

            <!-- Major Global Trans-Oceanic Flight Routes (Soft Arched White Dashes) -->
            <!-- London to New York -->
            <path d="M 1030,440 Q 820,380 670,540" fill="none" stroke="#ffffff" stroke-width="1.8" stroke-dasharray="6,6" opacity="0.65" />
            <!-- Paris to Tokyo -->
            <path d="M 1060,480 Q 1500,280 1980,560" fill="none" stroke="#ffffff" stroke-width="1.8" stroke-dasharray="6,6" opacity="0.65" />
            <!-- Tokyo to San Francisco -->
            <path d="M 1980,560 Q 2400,420 2860,540" fill="none" stroke="#ffffff" stroke-width="1.8" stroke-dasharray="6,6" opacity="0.5" />
            <!-- Beijing to Singapore -->
            <path d="M 1820,530 Q 1780,720 1740,940" fill="none" stroke="#ffffff" stroke-width="1.8" stroke-dasharray="6,6" opacity="0.65" />
            <!-- Singapore to Sydney -->
            <path d="M 1740,940 Q 1960,1080 2210,1220" fill="none" stroke="#ffffff" stroke-width="1.8" stroke-dasharray="6,6" opacity="0.65" />
          </g>
        </defs>

        <!-- Render 3 Consecutive World Tiles: tileOffsets = [-1, 0, 1] -->
        <use
          v-for="offset in tileOffsets"
          :key="`tile-${offset}`"
          href="#single-world-globe"
          :x="offset * WORLD_WIDTH"
          y="0"
        />
      </svg>

      <!-- Repeating Destination Pins for each World Tile -->
      <template v-for="offset in tileOffsets" :key="`pins-group-${offset}`">
        <div
          v-for="pin in pins"
          :key="`${pin.name}-${offset}`"
          class="world-pin-item"
          :style="{
            left: `${pin.x + offset * WORLD_WIDTH}px`,
            top: `${pin.y}px`
          }"
          @click.stop="emit('pin-click', pin)"
        >
          <div class="pin-bubble-card">
            <span class="pin-city-name">{{ pin.name }}</span>
            <strong class="pin-price-tag">{{ pin.price }}</strong>
          </div>
          <div class="pin-anchor-stem"></div>
        </div>
      </template>
    </div>

    <!-- Floating Top-Right Reset Compass -->
    <div class="map-floating-top-right" @mousedown.stop>
      <button class="circle-action-btn compass" title="重置视角" @click="resetView">
        <AppIcon name="compass" size="18" color="#0071e3" />
      </button>
    </div>

    <!-- Floating Bottom-Right Zoom Controls -->
    <div class="map-floating-bottom-right" @mousedown.stop>
      <div class="zoom-control-box">
        <button class="zoom-btn" title="放大" @click="zoomIn">＋</button>
        <div class="zoom-div"></div>
        <button class="zoom-btn" title="缩小" @click="zoomOut">−</button>
      </div>
    </div>

    <!-- Bottom Center Map Info -->
    <div class="map-floating-bottom-center" @mousedown.stop>
      <span>360° 环球无缝底图 · 任意拖拽永不留白</span>
    </div>
  </div>
</template>

<style scoped>
.world-map-viewport {
  position: absolute;
  inset: 0;
  width: 100vw;
  height: 100vh;
  overflow: hidden;
  background: #9ec9eb;
  cursor: grab;
  user-select: none;
}

.world-map-viewport.dragging {
  cursor: grabbing;
}

.world-pan-layer {
  position: absolute;
  left: 0;
  top: 0;
  width: 7680px;
  height: 1400px;
  will-change: transform;
}

.world-vector-svg {
  display: block;
  pointer-events: none;
}

/* Destination Pins with Price Tooltips */
.world-pin-item {
  position: absolute;
  transform: translate(-50%, -100%);
  cursor: pointer;
  z-index: 15;
  transition: transform 0.15s ease;
}

.world-pin-item:hover {
  transform: translate(-50%, -108%) scale(1.08);
  z-index: 25;
}

.pin-bubble-card {
  background: rgba(255, 255, 255, 0.94);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  border: 1px solid rgba(0, 0, 0, 0.08);
  border-radius: var(--radius-sm);
  padding: 4px 8px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.12);
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--text-primary);
  line-height: 1.2;
}

.world-pin-item:hover .pin-bubble-card {
  background: var(--theme-blue);
  color: #ffffff;
  border-color: var(--theme-blue);
}

.pin-city-name {
  font-size: 11px;
  color: var(--text-secondary);
}

.world-pin-item:hover .pin-city-name {
  color: rgba(255, 255, 255, 0.9);
}

.pin-price-tag {
  font-weight: 700;
  color: var(--price-color);
}

.world-pin-item:hover .pin-price-tag {
  color: #ffffff;
}

.pin-anchor-stem {
  width: 0;
  height: 0;
  border-left: 4px solid transparent;
  border-right: 4px solid transparent;
  border-top: 5px solid rgba(255, 255, 255, 0.94);
  margin: 0 auto;
}

.world-pin-item:hover .pin-anchor-stem {
  border-top-color: var(--theme-blue);
}

/* Floating Controls */
.map-floating-top-right {
  position: absolute;
  top: 16px;
  right: 16px;
  z-index: 25;
}

.circle-action-btn.compass {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.92);
  backdrop-filter: blur(20px);
  -webkit-backdrop-filter: blur(20px);
  border: 1px solid rgba(0, 0, 0, 0.08);
  display: grid;
  place-items: center;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.08);
  cursor: pointer;
  transition: all 0.15s ease;
}

.circle-action-btn.compass:hover {
  background: #ffffff;
  transform: scale(1.05);
}

.map-floating-bottom-right {
  position: absolute;
  bottom: 28px;
  right: 16px;
  z-index: 25;
}

.zoom-control-box {
  background: rgba(255, 255, 255, 0.92);
  backdrop-filter: blur(20px);
  -webkit-backdrop-filter: blur(20px);
  border: 1px solid rgba(0, 0, 0, 0.08);
  border-radius: var(--radius-sm);
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.08);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.zoom-btn {
  width: 36px;
  height: 34px;
  border: none;
  background: transparent;
  font-size: 16px;
  color: var(--text-primary);
  display: grid;
  place-items: center;
  cursor: pointer;
}

.zoom-btn:hover {
  background: rgba(0, 0, 0, 0.05);
}

.zoom-div {
  height: 1px;
  background: rgba(0, 0, 0, 0.08);
}

.map-floating-bottom-center {
  position: absolute;
  bottom: 8px;
  right: 16px;
  font-size: 10px;
  color: rgba(255, 255, 255, 0.85);
  text-shadow: 0 1px 2px rgba(0, 0, 0, 0.35);
  pointer-events: none;
  z-index: 25;
}
</style>
