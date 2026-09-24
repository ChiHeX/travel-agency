<script setup>
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import L from 'leaflet'
import 'leaflet/dist/leaflet.css'

const props = defineProps({
  itinerary: { type: Array, default: () => [] },
  drawerOpen: { type: Boolean, default: false },
  sidebarExpanded: { type: Boolean, default: true },
  sheetSize: { type: String, default: 'half' }
})

const mapElement = ref(null)
let mapInstance
let overlays
let hadPoints = false

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
  if (points().length) renderMap()
}

function points() {
  return props.itinerary
    .flatMap((day) => day.items || [])
    .filter((item) => item.longitude != null && item.latitude != null)
    .map((item, index) => ({
      position: [Number(item.latitude), Number(item.longitude)],
      name: item.name,
      order: index + 1
    }))
    .filter((item) => Number.isFinite(item.position[0]) && Number.isFinite(item.position[1])
      && Math.abs(item.position[0]) <= 90 && Math.abs(item.position[1]) <= 180)
}

function renderMap() {
  if (!mapElement.value) return
  if (!mapInstance) {
    mapInstance = L.map(mapElement.value, {
      zoomControl: false,
      zoomSnap: 0.25,
      worldCopyJump: true,
      // Keep the poles inside the viewport while allowing wrapped longitude.
      maxBounds: L.latLngBounds([-85.05112878, -900], [85.05112878, 900]),
      maxBoundsViscosity: 1
    })
    L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
      maxZoom: 19,
      attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'
    }).addTo(mapInstance)
    L.control.zoom({ position: 'topright' }).addTo(mapInstance)
    overlays = L.layerGroup().addTo(mapInstance)
    updateMinZoom()
    resetView()
  }

  const data = points()
  overlays.clearLayers()
  if (!data.length) {
    if (hadPoints) resetView()
    hadPoints = false
    return
  }
  hadPoints = true
  data.forEach((point) => {
    const icon = L.divIcon({
      className: 'route-marker',
      html: `<span>${point.order}</span>`,
      iconSize: [24, 24],
      iconAnchor: [12, 12]
    })
    L.marker(point.position, { icon, title: point.name })
      .bindPopup(`<strong>${point.order}. ${escapeHtml(point.name)}</strong>`)
      .addTo(overlays)
  })
  if (data.length > 1) {
    L.polyline(data.map((point) => point.position), {
      color: '#0071e3', weight: 5, opacity: 0.82
    }).addTo(overlays)
  }
  const isMobile = window.innerWidth <= 900
  const leftPadding = isMobile ? 24 : props.drawerOpen
    ? (props.sidebarExpanded ? 610 : 486)
    : (props.sidebarExpanded ? 210 : 86)
  mapInstance.fitBounds(L.latLngBounds(data.map((point) => point.position)), {
    paddingTopLeft: [leftPadding, 24],
    paddingBottomRight: [24, isMobile && props.drawerOpen
      ? (props.sheetSize === 'collapsed' ? 120 : Math.round(window.innerHeight * 0.58))
      : 24],
    maxZoom: 12
  })
}

function escapeHtml(value = '') {
  return String(value).replace(/[&<>'"]/g, (char) => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', "'": '&#39;', '"': '&quot;'
  })[char])
}

onMounted(() => {
  renderMap()
  window.addEventListener('resize', onMapResize)
})
watch(() => [props.itinerary, props.drawerOpen, props.sidebarExpanded, props.sheetSize], renderMap, { deep: true })
onBeforeUnmount(() => {
  window.removeEventListener('resize', onMapResize)
  mapInstance?.remove()
  mapInstance = undefined
  overlays = undefined
})
</script>

<template>
  <div class="map-preview-card" :class="{ 'drawer-open': drawerOpen, ['sheet-' + sheetSize]: drawerOpen }">
    <div ref="mapElement" class="map-canvas"></div>
    <button class="map-reset" type="button" aria-label="重置地图视角" @click="resetView">重置视角</button>
  </div>
</template>

<style scoped>
.map-preview-card {
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
  top: 88px;
  right: 10px;
  padding: 6px 8px;
  border: 1px solid #bbb;
  border-radius: 4px;
  background: #fff;
  color: #333;
  font-size: 12px;
  cursor: pointer;
}

@media (max-width: 900px) {
  .map-preview-card { top: 52px; }
  .map-preview-card.drawer-open.sheet-half :deep(.leaflet-bottom) { bottom: calc(55vh + 14px); }
  .map-preview-card.drawer-open.sheet-collapsed :deep(.leaflet-bottom) { bottom: 110px; }
}
</style>
