// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import L from 'leaflet'
import MapPreview from '../MapPreview.vue'

let wrapper

beforeEach(() => {
  localStorage.clear()
  vi.stubEnv('VITE_MAP_PROVIDER', 'tianditu')
  vi.stubEnv('VITE_TIANDITU_KEY', 'test-key')
  vi.spyOn(HTMLElement.prototype, 'clientWidth', 'get').mockReturnValue(1000)
  vi.spyOn(HTMLElement.prototype, 'clientHeight', 'get').mockReturnValue(700)
})

afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
  vi.restoreAllMocks()
  vi.unstubAllEnvs()
})

describe('MapPreview 真实 Leaflet 图层生命周期', () => {
  it('重复切换后仍能连续定位，旧图层不再响应地图事件或保留署名', async () => {
    const mapSpy = vi.spyOn(L, 'map')
    wrapper = mount(MapPreview, { attachTo: document.body })
    const map = mapSpy.mock.results[0].value
    for (const provider of ['osm', 'tianditu', 'osm', 'tianditu']) {
      await wrapper.get(`[data-provider="${provider}"]`).trigger('click')
      // setView 触发真实 viewreset / move / zoom 事件，覆盖被移除图层的清理。
      expect(() => map.setView([30.24, 120.13], 12, { animate: false })).not.toThrow()
      expect(() => map.setView([39.9, 116.4], 10, { animate: false })).not.toThrow()
      const attribution = wrapper.get('.leaflet-control-attribution').text()
      expect(attribution.includes('天地图')).toBe(provider === 'tianditu')
      expect(attribution.includes('OpenStreetMap')).toBe(provider === 'osm')
    }
  })
})
