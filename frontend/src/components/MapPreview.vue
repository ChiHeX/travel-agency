<script setup>
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'

const props = defineProps({
  itinerary: { type: Array, default: () => [] }
})

const mapElement = ref(null)
const hasAmapKey = Boolean(import.meta.env.VITE_AMAP_KEY)
const mapLoadError = ref('')
let mapInstance
let overlays = []
let infoWindow
let amapLoader

function loadAmap(key) {
  if (window.AMap) return Promise.resolve(window.AMap)
  if (amapLoader) return amapLoader

  amapLoader = new Promise((resolve, reject) => {
    const existing = document.querySelector('script[data-travel-amap]')
    const script = existing || document.createElement('script')
    if (!existing) {
      script.dataset.travelAmap = 'true'
      script.src = `https://webapi.amap.com/maps?v=2.0&key=${encodeURIComponent(key)}`
    }
    script.addEventListener('load', () => resolve(window.AMap), { once: true })
    script.addEventListener('error', () => reject(new Error('高德地图脚本加载失败')), { once: true })
    if (!existing) document.head.appendChild(script)
  })
  return amapLoader
}

function points() {
  return props.itinerary
    .flatMap((day) => day.items || [])
    .filter((item) => item.longitude != null && item.latitude != null)
    .map((item, index) => ({
      position: [Number(item.longitude), Number(item.latitude)],
      name: item.name,
      order: index + 1
    }))
    .filter((item) => item.position.every(Number.isFinite))
}

function renderMap() {
  const key = import.meta.env.VITE_AMAP_KEY
  if (!key || !mapElement.value || !window.AMap) return
  const data = points()
  if (!data.length) {
    if (mapInstance && overlays.length) mapInstance.remove(overlays)
    overlays = []
    return
  }
  if (!mapInstance) {
    mapInstance = new window.AMap.Map(mapElement.value, {
      zoom: 6,
      center: data[0].position,
      mapStyle: 'amap://styles/whitesmoke'
    })
  }
  if (overlays.length) mapInstance.remove(overlays)

  const markers = data.map((point) => {
    const marker = new window.AMap.Marker({
      position: point.position,
      title: point.name,
      label: { content: String(point.order), direction: 'center' }
    })
    marker.on('click', () => {
      infoWindow ||= new window.AMap.InfoWindow({ offset: new window.AMap.Pixel(0, -28) })
      infoWindow.setContent(`<div class="amap-route-info"><strong>${point.order}. ${escapeHtml(point.name)}</strong></div>`)
      infoWindow.open(mapInstance, point.position)
    })
    return marker
  })
  overlays = [...markers]
  if (data.length > 1) {
    overlays.push(new window.AMap.Polyline({
      path: data.map((point) => point.position),
      strokeColor: '#0071e3',
      strokeWeight: 5,
      strokeOpacity: 0.82,
      lineJoin: 'round',
      lineCap: 'round',
      showDir: true
    }))
  }
  mapInstance.add(overlays)
  mapInstance.setFitView(overlays, false, [42, 42, 42, 42], 15)
}

function escapeHtml(value = '') {
  return String(value).replace(/[&<>'"]/g, (char) => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', "'": '&#39;', '"': '&quot;'
  })[char])
}

onMounted(() => {
  const key = import.meta.env.VITE_AMAP_KEY
  if (!key) return
  window._AMapSecurityConfig = { securityJsCode: import.meta.env.VITE_AMAP_SECURITY_CODE || '' }
  loadAmap(key).then(renderMap).catch((error) => {
    mapLoadError.value = error.message
  })
})

watch(() => props.itinerary, renderMap, { deep: true })
onBeforeUnmount(() => {
  infoWindow?.close()
  mapInstance?.destroy()
  mapInstance = undefined
  overlays = []
})
</script>

<template>
  <div class="map-preview-card">
    <div ref="mapElement" class="map-canvas"></div>
    <div v-if="!hasAmapKey" class="map-fallback-view">
      <div class="sheet-text">
        <strong>地图服务待配置</strong>
        <p>配置 VITE_AMAP_KEY 后，此处将根据行程项目的真实经纬度显示景点与路线。</p>
      </div>
      <div class="map-badge-tag">地图数据来自行程项目坐标 · 高德地图开放平台</div>
    </div>
    <div v-else-if="mapLoadError" class="map-fallback-view no-coords">
      <div class="sheet-text">
        <strong>地图服务加载失败</strong>
        <p>{{ mapLoadError }}，请稍后刷新页面。</p>
      </div>
    </div>
    <div v-else-if="!points().length" class="map-fallback-view no-coords">
      <div class="sheet-text">
        <strong>暂无经纬度坐标</strong>
        <p>在后台行程项中录入景点经纬度即可自动生成互动路线。</p>
      </div>
    </div>
  </div>
</template>

<style scoped>
.map-preview-card {
  position: relative;
  height: 360px;
  border-radius: var(--radius-lg);
  overflow: hidden;
  border: 1px solid var(--border-line);
  background: #f8fafc;
  box-shadow: var(--shadow-xs);
}

.map-canvas {
  width: 100%;
  height: 100%;
}

.map-canvas :deep(.amap-marker-label) {
  display: grid;
  width: 20px;
  height: 20px;
  place-items: center;
  border: 2px solid #fff;
  border-radius: 50%;
  background: #0071e3;
  box-shadow: 0 2px 7px rgba(0, 72, 153, 0.3);
  color: #fff;
  font-size: 10px;
  font-weight: 700;
}

.map-canvas :deep(.amap-route-info) {
  padding: 2px 4px;
  color: #1d1d1f;
  font-size: 12px;
}

.map-fallback-view {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  justify-content: flex-end;
  padding: 20px;
}

.map-fallback-view.no-coords {
  justify-content: center;
  align-items: center;
  text-align: center;
}

.sheet-text strong {
  display: block;
  font-size: 13px;
  font-weight: 700;
  color: var(--text-primary);
  margin-bottom: 2px;
}

.sheet-text p {
  font-size: 12px;
  color: var(--text-secondary);
  line-height: 1.4;
  margin: 0;
}

.map-badge-tag {
  position: absolute;
  top: 14px;
  right: 14px;
  z-index: 2;
  background: rgba(255, 255, 255, 0.9);
  backdrop-filter: blur(8px);
  padding: 4px 10px;
  border-radius: var(--radius-full);
  font-size: 10px;
  font-weight: 600;
  color: var(--text-tertiary);
  border: 1px solid var(--border-line);
}
</style>
