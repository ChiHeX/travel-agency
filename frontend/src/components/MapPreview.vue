<script setup>
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import L from 'leaflet'
import 'leaflet/dist/leaflet.css'
import { mapTileSources } from '@/api/mapTiles'

const props = defineProps({
  itinerary: { type: Array, default: () => [] },
  places: { type: Array, default: () => [] },
  focusedPlace: { type: Object, default: null },
  drawerOpen: { type: Boolean, default: false },
  sidebarExpanded: { type: Boolean, default: true },
  sheetHeight: { type: Number, default: 0 }
})
const mapElement = ref(null)
const mapProvider = ref(import.meta.env.VITE_MAP_PROVIDER === 'osm' ? 'osm' : 'tianditu')
const mapMessage = ref('')
const tiandituKey = (import.meta.env.VITE_TIANDITU_KEY || '').trim()
try {
  const saved = window.localStorage.getItem('travel-agency-map-provider')
  if (saved === 'osm' || saved === 'tianditu') mapProvider.value = saved
} catch { /* 浏览器禁用存储时仍允许切换地图。 */ }
let mapInstance
let overlays
let baseLayers = []

function updateMapProvider() {
  if (!mapInstance) return
  // remove 事件负责解绑 Leaflet 的地图监听与署名，必须先移除再清理事件。
  baseLayers.forEach((layer) => { layer.remove(); layer.off() })
  baseLayers = []
  mapMessage.value = mapProvider.value === 'tianditu' && !tiandituKey
    ? '天地图尚未配置，暂时无法显示底图，可切换 OpenStreetMap。' : ''
  baseLayers = mapTileSources(mapProvider.value, tiandituKey).map(({ url, options }) => {
    const layer = L.tileLayer(url, options)
    layer.on('tileerror', () => {
      mapMessage.value = mapProvider.value === 'tianditu'
        ? '天地图加载失败，请检查网络或地图服务授权，也可切换地图源。'
        : 'OpenStreetMap 加载失败，请检查网络或切换天地图。'
    })
    return layer.addTo(mapInstance)
  })
}

watch(mapProvider, () => {
  updateMapProvider()
  try {
    window.localStorage.setItem('travel-agency-map-provider', mapProvider.value)
  } catch { /* 存储不可用不影响地图。 */ }
})

function updateMinZoom() {
  if (!mapInstance) return
  const size = mapInstance.getSize()
  // Longitude wraps, so only the map height needs to fit inside one world.
  const minZoom = Math.max(0, Math.ceil(Math.log2((size.y + 2) / 256) * 4) / 4)
  mapInstance.setMinZoom(minZoom)
}

function resetView() {
  if (!mapInstance) return
  mapInstance.setView([30, 105], Math.max(4, mapInstance.getMinZoom()))
}

function onMapResize() {
  if (!mapInstance) return
  mapInstance.invalidateSize({ pan: false })
  updateMinZoom()
  renderMap()
}

function points() {
  if (props.places.length) {
    return props.places
      .filter((item) => item.longitude != null && item.latitude != null)
      .map((item, index) => ({
        position: [Number(item.latitude), Number(item.longitude)],
        name: item.name,
        order: index + 1
      }))
      .filter((item) => Number.isFinite(item.position[0]) && Number.isFinite(item.position[1])
        && Math.abs(item.position[0]) <= 90 && Math.abs(item.position[1]) <= 180)
  }
  // 行程点位先过滤掉经纬度不成对、非数字和越界（历史数据）的点，再编号：
  // 编号是给用户看的"第几站"，被丢弃的点不该占用编号（否则第二个可见点会显示成"第 4 站"）。
  return props.itinerary
    .flatMap((day) => day.items || [])
    .filter((item) => item.longitude != null && item.latitude != null)
    .map((item) => ({
      position: [Number(item.latitude), Number(item.longitude)],
      name: item.name
    }))
    .filter((item) => Number.isFinite(item.position[0]) && Number.isFinite(item.position[1])
      && Math.abs(item.position[0]) <= 90 && Math.abs(item.position[1]) <= 180)
    .map((item, index) => ({ ...item, order: index + 1 }))
}

function renderMap() {
  if (!mapElement.value) return
  if (!mapInstance) {
    mapInstance = L.map(mapElement.value, {
      zoomControl: false,
      maxZoom: 19,
      zoomSnap: 0.25,
      worldCopyJump: true,
      // Keep the poles inside the viewport while allowing wrapped longitude.
      maxBounds: L.latLngBounds([-85.05112878, -900], [85.05112878, 900]),
      maxBoundsViscosity: 1
    })
    updateMapProvider()
    L.control.zoom({ position: 'topright' }).addTo(mapInstance)
    overlays = L.layerGroup().addTo(mapInstance)
    updateMinZoom()
    resetView()
  }

  const data = points()
  overlays.clearLayers()
  if (!data.length) {
    focusPlace()
    return
  }
  if (!props.places.length) {
    data.forEach((point) => {
      const icon = L.divIcon({
        className: 'route-marker',
        html: `<span>${point.order}</span>`,
        iconSize: [24, 24],
        iconAnchor: [12, 12]
      })
      const marker = L.marker(point.position, { icon, title: point.name })
      marker.bindPopup(`<strong>${point.order}. ${escapeHtml(point.name)}</strong>`)
      marker.addTo(overlays)
    })
    if (data.length > 1) {
      L.polyline(data.map((point) => point.position), {
        color: '#0071e3', weight: 4, opacity: 0.82, dashArray: '8 8'
      }).addTo(overlays)
    }
  }
  const isMobile = window.innerWidth <= 900
  const leftPadding = isMobile ? 24 : props.drawerOpen
    ? (props.sidebarExpanded ? 610 : 486)
    : (props.sidebarExpanded ? 210 : 86)
  mapInstance.fitBounds(L.latLngBounds(data.map((point) => point.position)), {
    paddingTopLeft: [leftPadding, 24],
    paddingBottomRight: [24, isMobile && props.drawerOpen
      ? Math.min(props.sheetHeight + 24, Math.max(24, window.innerHeight - 160))
      : 24],
    maxZoom: 12
  })
  focusPlace()
}

function escapeHtml(value = '') {
  return String(value).replace(/[&<>'"]/g, (char) => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', "'": '&#39;', '"': '&quot;'
  })[char])
}

function focusPlace() {
  if (!mapInstance) return
  if (!props.focusedPlace) {
    if (!points().length) overlays.clearLayers()
    return
  }
  if (props.focusedPlace.latitude == null || props.focusedPlace.longitude == null) return
  const latitude = Number(props.focusedPlace.latitude)
  const longitude = Number(props.focusedPlace.longitude)
  if (Number.isFinite(latitude) && Number.isFinite(longitude) &&
    Math.abs(latitude) <= 90 && Math.abs(longitude) <= 180) {
    overlays.clearLayers()
    const marker = L.circleMarker([latitude, longitude], {
      radius: 8, color: '#fff', weight: 3, fillColor: '#0071e3', fillOpacity: 1
    }).addTo(overlays)
    if (props.focusedPlace.name) {
      marker.bindTooltip(escapeHtml(props.focusedPlace.name), { permanent: true, direction: 'top', offset: [0, -10] })
    }
    const zoom = Math.max(12, mapInstance.getZoom())
    const isMobile = window.innerWidth <= 900
    const leftOffset = isMobile || !props.drawerOpen ? 0 : (props.sidebarExpanded ? 305 : 243)
    const bottomOffset = isMobile && props.drawerOpen
      ? props.sheetHeight / 2
      : 0
    const center = mapInstance.project([latitude, longitude], zoom)
      .subtract(L.point(leftOffset, -bottomOffset))
    mapInstance.flyTo(mapInstance.unproject(center, zoom), zoom)
  }
}

onMounted(() => {
  renderMap()
  window.addEventListener('resize', onMapResize)
})
watch(() => props.itinerary, renderMap, { deep: true })
watch(() => props.places, renderMap, { deep: true })
watch(() => props.focusedPlace, focusPlace)
onBeforeUnmount(() => {
  window.removeEventListener('resize', onMapResize)
  mapInstance?.remove()
  baseLayers.forEach((layer) => layer.off())
  baseLayers = []
  mapInstance = undefined
  overlays = undefined
})
</script>

<template>
  <div class="map-preview-card" :class="{ 'drawer-open': drawerOpen }" :style="{ '--map-sheet-height': `${sheetHeight}px` }">
    <div ref="mapElement" class="map-canvas"></div>
    <button class="map-reset" type="button" aria-label="重置地图视角" title="重置地图视角" @click="resetView">
      <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2" stroke-linejoin="round" aria-hidden="true">
        <path d="M4 14 21 4l-5 17-3.5-7.5L4 14Z" />
      </svg>
    </button>
    <div class="map-source-control" role="group" aria-label="地图源">
      <button type="button" data-provider="tianditu" aria-label="切换天地图" title="天地图"
              :aria-pressed="mapProvider === 'tianditu'" @click="mapProvider = 'tianditu'">天地图</button>
      <button type="button" data-provider="osm" aria-label="切换 OpenStreetMap" title="OpenStreetMap"
              :aria-pressed="mapProvider === 'osm'" @click="mapProvider = 'osm'">OSM</button>
      <p v-if="mapMessage" role="status">{{ mapMessage }}</p>
    </div>
  </div>
</template>

<style scoped>
.map-preview-card {
  --map-bottom-offset: 0px;
  position: absolute;
  inset: 0;
  z-index: 0;
  overflow: hidden;
  background: #f8fafc;
}

.map-canvas {
  width: 100%;
  height: 100%;
}

.map-source-control {
  position: absolute;
  z-index: 500;
  bottom: calc(var(--map-bottom-offset) + 30px);
  right: 10px;
  display: flex;
  flex-direction: column;
  border-radius: 4px;
  background: #fff;
  box-shadow: 0 1px 5px rgba(0, 0, 0, 0.65);
  font-size: 12px;
}
.map-source-control button {
  display: grid;
  place-items: center;
  width: 56px;
  height: 26px;
  padding: 0;
  border: 0;
  background: #fff;
  color: #000;
  font-size: 12px;
  font-weight: 700;
  cursor: pointer;
}
.map-source-control button:first-child { border-radius: 4px 4px 0 0; border-bottom: 1px solid #ccc; }
.map-source-control button:nth-child(2) { border-radius: 0 0 4px 4px; }
.map-source-control button:hover,
.map-source-control button:focus-visible { background: #f4f4f4; }
.map-source-control button[aria-pressed="true"] { background: #e8f2ff; color: #0071e3; }
.map-source-control p {
  position: absolute;
  right: calc(100% + 8px);
  bottom: 0;
  width: min(220px, calc(100vw - 70px));
  margin: 0;
  padding: 8px;
  border-radius: 4px;
  background: #fff;
  box-shadow: 0 1px 5px rgba(0, 0, 0, 0.25);
  line-height: 1.5;
  color: #92400e;
}
.map-canvas.leaflet-touch ~ .map-source-control {
  border: 2px solid rgba(0, 0, 0, 0.2);
  box-shadow: none;
}
.map-canvas.leaflet-touch ~ .map-source-control button { height: 30px; }

.map-canvas :deep(.route-marker) {
  display: grid;
  place-items: center;
  border: 2px solid #fff;
  border-radius: 50%;
  background: #0071e3;
  box-shadow: 0 2px 7px rgba(0, 72, 153, 0.3);
  color: #fff;
  font-size: 12px;
  font-weight: 700;
}

.map-reset {
  position: absolute;
  z-index: 500;
  top: 74px;
  right: 10px;
  display: grid;
  place-items: center;
  width: 26px;
  height: 26px;
  padding: 0;
  border: 0;
  border-radius: 4px;
  background: #fff;
  box-shadow: 0 1px 5px rgba(0, 0, 0, 0.65);
  color: #000;
  cursor: pointer;
}

.map-reset:hover,
.map-reset:focus-visible { background: #f4f4f4; }

.map-canvas.leaflet-touch + .map-reset {
  top: 84px;
  box-sizing: border-box;
  width: 34px;
  height: 34px;
  border: 2px solid rgba(0, 0, 0, 0.2);
  border-radius: 4px;
  box-shadow: none;
}

@media (max-width: 900px) {
  .map-preview-card { top: 52px; }
  .map-preview-card.drawer-open { --map-bottom-offset: calc(var(--map-sheet-height) + 14px); }
  .map-preview-card :deep(.leaflet-bottom) { bottom: var(--map-bottom-offset); }
}
</style>
