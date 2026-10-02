// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest'
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
  const calls = { markers: [], polylines: [], circles: [], fitBounds: [], clearLayers: 0 }
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
    tileLayer: () => ({ addTo: () => {} }),
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
      calls.circles.push({ position, options })
      return { addTo: () => {} }
    }
  }
  return { calls, L }
})

vi.mock('leaflet', () => ({ default: leaflet.L }))

const calls = leaflet.calls

function mountMap(props = {}) {
  return mount(MapPreview, { props, attachTo: document.body })
}

beforeEach(() => {
  calls.markers.length = 0
  calls.polylines.length = 0
  calls.circles.length = 0
  calls.fitBounds.length = 0
  calls.clearLayers = 0
})

describe('MapPreview 地图坐标消费规则', () => {
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
})
