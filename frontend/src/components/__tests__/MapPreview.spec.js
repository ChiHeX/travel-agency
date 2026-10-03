// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import MapPreview from '../MapPreview.vue'

/**
 * 用户端地图（Leaflet + OpenStreetMap）对"地图坐标数据"的消费规则测试。
 *
 * <p>坐标数据的验收标准就在这个组件里，而它此前没有任何测试。这里钉住四条实际影响地图正确性的规则：</p>
 * <ol>
 *   <li><b>经纬度必须都非空</b>：只填一个的点无法落点。后端已按 {@code CoordinatePairRule}
 *       拒绝半截坐标，但历史数据与越界值仍要在这里被丢弃，而不是画到几内亚湾或者抛异常；</li>
 *   <li>行程模式按行程顺序标注并连线：Marker 编号与 Polyline 顶点顺序一致（跨天展开后仍是行程顺序）；</li>
 *   <li>地点指南模式只用景点坐标确定聚焦区域，<b>不</b>画 Marker 与连线；</li>
 *   <li>单点聚焦（focusedPlace）必须画出圆点标记；坐标不成对时不画。</li>
 * </ol>
 *
 * <p>Leaflet 在 jsdom 里无法真正渲染瓦片，因此这里替换掉 leaflet 模块，
 * 只断言"组件要求地图画了什么"。</p>
 */

const leaflet = vi.hoisted(() => {
  const calls = { markers: [], polylines: [], circles: [], fitBounds: [], tiles: [], clearLayers: 0 }
  const overlays = { addTo: () => overlays, clearLayers: () => { calls.clearLayers += 1 } }
  const map = {
    setView: () => {},
    setMinZoom: () => {},
    getMinZoom: () => 4,
    getZoom: () => 6,
    getSize: () => ({ x: 800, y: 600 }),
    invalidateSize: () => {},
    flyTo: () => {},
    remove: () => {},
    fitBounds: (...args) => calls.fitBounds.push(args),
    project: () => ({ subtract: () => ({ x: 0, y: 0 }) }),
    unproject: () => ({ lat: 30, lng: 105 })
  }
  const L = {
    map: () => map,
    tileLayer: (url, options) => {
      const layer = { url, options, events: {}, off: vi.fn(), remove: vi.fn() }
      layer.on = (event, handler) => { layer.events[event] = handler; return layer }
      layer.addTo = () => layer
      calls.tiles.push(layer)
      return layer
    },
    control: { zoom: () => ({ addTo: () => {} }) },
    layerGroup: () => overlays,
    divIcon: (options) => options,
    latLngBounds: (bounds) => bounds,
    point: (x, y) => ({ x, y, subtract: () => ({ x, y }) }),
    marker: (position, options) => {
      const marker = { position, options, popup: '' }
      marker.addTo = () => marker
      marker.bindPopup = (html) => { marker.popup = html; return marker }
      calls.markers.push(marker)
      return marker
    },
    polyline: (positions, options) => {
      calls.polylines.push({ positions, options })
      return { addTo: () => {} }
    },
    circleMarker: (position, options) => {
      const marker = { position, options }
      marker.addTo = () => marker
      marker.bindTooltip = (text) => { marker.tooltip = text; return marker }
      calls.circles.push(marker)
      return marker
    }
  }
  return { calls, L }
})

vi.mock('leaflet', () => ({ default: leaflet.L }))

const calls = leaflet.calls

const mountedMaps = []
function mountMap(props = {}) {
  const wrapper = mount(MapPreview, { props, attachTo: document.body })
  mountedMaps.push(wrapper)
  return wrapper
}
afterEach(() => {
  mountedMaps.splice(0).forEach((wrapper) => wrapper.unmount())
  vi.unstubAllEnvs()
})

beforeEach(() => {
  window.localStorage.clear()
  vi.stubEnv('VITE_MAP_PROVIDER', 'tianditu')
  vi.stubEnv('VITE_TIANDITU_KEY', '')
  calls.tiles.length = 0
  calls.markers.length = 0
  calls.polylines.length = 0
  calls.circles.length = 0
  calls.fitBounds.length = 0
  calls.clearLayers = 0
})

describe('MapPreview 地图坐标消费规则', () => {
  it('未配置 Key 时提示，不发送无授权的天地图请求；仍可切换 OSM', async () => {
    const wrapper = mountMap()
    await wrapper.vm.$nextTick()
    expect(wrapper.text()).toContain('天地图尚未配置')
    expect(calls.tiles).toHaveLength(0)
    await wrapper.get('[data-provider=osm]').trigger('click')
    expect(calls.tiles[0].url).toBe('https://tile.openstreetmap.org/{z}/{x}/{y}.png')
    expect(calls.tiles[0].options.attribution).toContain('openstreetmap.org/copyright')
    expect(wrapper.find('[role="status"]').exists()).toBe(false)
  })

  it('天地图加载底图与中文注记；切换仅移除底图，保留业务覆盖物和视角', async () => {
    vi.stubEnv('VITE_TIANDITU_KEY', ' test-key ')
    const wrapper = mountMap({ itinerary: [{ items: [
      { name: '西湖', longitude: 120.13, latitude: 30.24 }
    ] }] })
    expect(calls.tiles).toHaveLength(2)
    expect(calls.tiles[0].url).toContain('/vec_w/wmts?')
    expect(calls.tiles[1].url).toContain('/cva_w/wmts?')
    for (const layer of calls.tiles) {
      expect(layer.url).toContain('TILEMATRIXSET=w')
      expect(layer.url).toContain('TILEROW={y}&TILECOL={x}&tk=test-key')
      expect(layer.options.maxNativeZoom).toBe(18)
    }
    const originalLayers = [...calls.tiles]
    const clearCount = calls.clearLayers
    const fitCount = calls.fitBounds.length
    await wrapper.get('[data-provider=osm]').trigger('click')
    originalLayers.forEach((layer) => expect(layer.remove).toHaveBeenCalledOnce())
    expect(calls.clearLayers).toBe(clearCount)
    expect(calls.fitBounds).toHaveLength(fitCount)
    expect(window.localStorage.getItem('travel-agency-map-provider')).toBe('osm')
    await wrapper.get('[data-provider=tianditu]').trigger('click')
    expect(calls.tiles[2].remove).toHaveBeenCalledOnce()
    expect(calls.tiles).toHaveLength(5)
  })

  it('恢复地图源偏好；加载失败可提示并在切换后清除', async () => {
    window.localStorage.setItem('travel-agency-map-provider', 'osm')
    const wrapper = mountMap()
    expect(wrapper.get('[data-provider=osm]').attributes('aria-pressed')).toBe('true')
    calls.tiles[0].events.tileerror()
    await wrapper.vm.$nextTick()
    expect(wrapper.text()).toContain('OpenStreetMap 加载失败')
    await wrapper.get('[data-provider=tianditu]').trigger('click')
    expect(wrapper.text()).toContain('天地图尚未配置')
    expect(wrapper.text()).not.toContain('OpenStreetMap 加载失败')
  })

  it('配置 OSM 为默认源；存储不可用时仍能切换', async () => {
    vi.stubEnv('VITE_MAP_PROVIDER', 'osm')
    const read = vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('blocked') })
    const write = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('blocked') })
    try {
      const wrapper = mountMap()
      expect(wrapper.get('[data-provider=osm]').attributes('aria-pressed')).toBe('true')
      await wrapper.get('[data-provider=tianditu]').trigger('click')
      expect(wrapper.text()).toContain('天地图尚未配置')
    } finally {
      read.mockRestore()
      write.mockRestore()
    }
  })

  it('行程模式：按行程顺序标注并连线，半截坐标与越界坐标一律丢弃', () => {
    mountMap({
      itinerary: [
        {
          dayNumber: 1,
          items: [
            { name: '抵达昆明', longitude: 102.712, latitude: 25.04 },
            { name: '只有经度', longitude: 100.1, latitude: null },
            { name: '越界经度', longitude: 200, latitude: 10 },
            { name: '越界纬度', longitude: 100.2, latitude: 91 }
          ]
        },
        {
          dayNumber: 2,
          items: [
            { name: '大理古城', longitude: 100.165, latitude: 25.694 },
            { name: '非数字坐标', longitude: '东经一百度', latitude: 25.7 }
          ]
        }
      ]
    })

    expect(calls.markers.map((marker) => marker.position))
      .toEqual([[25.04, 102.712], [25.694, 100.165]])
    // 编号图标与 title 都按行程顺序生成，且只包含有效坐标点。
    expect(calls.markers.map((marker) => marker.options.icon.html))
      .toEqual(['<span>1</span>', '<span>2</span>'])
    expect(calls.markers.map((marker) => marker.options.title))
      .toEqual(['抵达昆明', '大理古城'])
    expect(calls.markers[0].popup).toContain('1. 抵达昆明')
    expect(calls.polylines).toHaveLength(1)
    expect(calls.polylines[0].positions).toEqual([[25.04, 102.712], [25.694, 100.165]])
  })

  it('行程模式：只剩一个有效坐标点时只标注、不连线', () => {
    mountMap({
      itinerary: [{ dayNumber: 1, items: [
        { name: '大理古城', longitude: 100.165, latitude: 25.694 },
        { name: '只有纬度', longitude: null, latitude: 25.7 }
      ] }]
    })

    expect(calls.markers).toHaveLength(1)
    expect(calls.polylines).toHaveLength(0)
  })

  it('地点指南模式：只用景点坐标确定聚焦区域，不画标记与连线', () => {
    mountMap({
      places: [
        { name: '西湖', longitude: 120.13, latitude: 30.24 },
        { name: '灵隐寺', longitude: 120.1, latitude: 30.241 },
        { name: '无坐标地点', longitude: null, latitude: null }
      ]
    })

    expect(calls.markers).toHaveLength(0)
    expect(calls.polylines).toHaveLength(0)
    expect(calls.fitBounds).toHaveLength(1)
    expect(calls.fitBounds[0][0]).toEqual([[30.24, 120.13], [30.241, 120.1]])
  })

  it('单点聚焦：坐标成对时画圆点，只填一个时不画', async () => {
    const wrapper = mountMap({ focusedPlace: { longitude: 120.13, latitude: null } })
    expect(calls.circles).toHaveLength(0)

    await wrapper.setProps({ focusedPlace: { longitude: 120.13, latitude: 30.24 } })
    expect(calls.circles).toHaveLength(1)
    expect(calls.circles[0].position).toEqual([30.24, 120.13])
  })
  it('初次打开与窗口尺寸变化后均保留酒店落点和名称，并转义名称内容', () => {
    const wrapper = mountMap({ focusedPlace: { name: '酒店 <A>', longitude: 120.139, latitude: 30.229 } })
    expect(calls.circles).toHaveLength(1)
    expect(calls.circles[0].tooltip).toBe('酒店 &lt;A&gt;')
    window.dispatchEvent(new Event('resize'))
    expect(calls.circles).toHaveLength(2)
    expect(calls.circles[1].position).toEqual([30.229, 120.139])
    expect(calls.circles[1].tooltip).toBe('酒店 &lt;A&gt;')
    wrapper.unmount()
  })

})
