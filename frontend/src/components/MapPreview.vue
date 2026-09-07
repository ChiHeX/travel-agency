<script setup>
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'

const props = defineProps({
  itinerary: { type: Array, default: () => [] }
})

const mapElement = ref(null)
const hasAmapKey = Boolean(import.meta.env.VITE_AMAP_KEY)
let mapInstance

function points() {
  return props.itinerary
    .flatMap((day) => day.items || [])
    .filter((item) => item.longitude != null && item.latitude != null)
    .map((item) => [Number(item.longitude), Number(item.latitude), item.name])
}

function renderMap() {
  const key = import.meta.env.VITE_AMAP_KEY
  if (!key || !mapElement.value || !window.AMap) return
  const data = points()
  mapInstance?.destroy()
  if (!data.length) return
  mapInstance = new window.AMap.Map(mapElement.value, {
    zoom: 6,
    center: data[0].slice(0, 2),
    mapStyle: 'amap://styles/whitesmoke'
  })
  const markers = data.map(([lng, lat, name]) => new window.AMap.Marker({ position: [lng, lat], title: name }))
  if (markers.length) mapInstance.add(markers)
  if (data.length > 1) {
    mapInstance.add(
      new window.AMap.Polyline({
        path: data.map(([lng, lat]) => [lng, lat]),
        strokeColor: '#0071e3',
        strokeWeight: 4,
        strokeOpacity: 0.85
      })
    )
  }
}

onMounted(() => {
  const key = import.meta.env.VITE_AMAP_KEY
  if (!key) return
  window._AMapSecurityConfig = { securityJsCode: import.meta.env.VITE_AMAP_SECURITY_CODE || '' }
  if (window.AMap) return renderMap()
  const script = document.createElement('script')
  script.src = `https://webapi.amap.com/maps?v=2.0&key=${key}`
  script.onload = renderMap
  document.head.appendChild(script)
})

watch(() => props.itinerary, renderMap, { deep: true })
onBeforeUnmount(() => mapInstance?.destroy())
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
