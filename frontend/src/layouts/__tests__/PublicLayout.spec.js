// @vitest-environment jsdom
import { beforeEach, afterEach, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import PublicLayout from '../PublicLayout.vue'

vi.mock('@/stores/auth', () => ({ useAuthStore: () => ({ isLoggedIn: false, hasRole: () => false }) }))
vi.mock('@/api/modules', () => ({ accountApi: {} }))
let height, resize, wrapper
beforeEach(() => {
  height = 800
  vi.spyOn(HTMLElement.prototype, 'clientHeight', 'get').mockImplementation(() => height)
  vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockReturnValue({ height: 52 })
  vi.stubGlobal('ResizeObserver', class {
    constructor(callback) { resize = callback }
    observe() {}
    disconnect() {}
  })
  HTMLElement.prototype.setPointerCapture = vi.fn()
  HTMLElement.prototype.hasPointerCapture = vi.fn(() => true)
  HTMLElement.prototype.releasePointerCapture = vi.fn()
})
afterEach(() => {
  wrapper?.unmount()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
  delete HTMLElement.prototype.setPointerCapture
  delete HTMLElement.prototype.hasPointerCapture
  delete HTMLElement.prototype.releasePointerCapture
})
async function open() {
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/search', name: 'search', component: { template: '<p>搜索内容</p>' } }
  ] })
  await router.push('/search')
  wrapper = mount(PublicLayout, { global: { plugins: [router], stubs: { RouterLink: true, MapPreview: true, AppIcon: true, AccountNav: true } } })
  return wrapper
}
const pointer = (clientY, pointerId = 1) => ({ clientY, pointerId, isPrimary: true, button: 0 })
const sheetHeight = () => wrapper.get('.drawer-track-wrapper').element.style.getPropertyValue('--sheet-height')
async function drag(handle, type, y) {
  const event = new Event(type, { bubbles: true })
  Object.assign(event, pointer(y))
  handle.element.dispatchEvent(event)
  await wrapper.vm.$nextTick()
}

it('removes mobile brand and preset height actions', async () => {
  await open()
  expect(wrapper.get('.mobile-navigation').text()).not.toContain('行迹')
  expect(wrapper.find('.sheet-size-actions').exists()).toBe(false)
})

it('resizes continuously during dragging and retains the released height without snapping', async () => {
  await open()
  const handle = wrapper.get('.sheet-resize-handle')
  await drag(handle, 'pointerdown', 400)
  await drag(handle, 'pointermove', 340)
  expect(sheetHeight()).toBe('500px')
  await drag(handle, 'pointerup', 327)
  expect(sheetHeight()).toBe('513px')
  await handle.trigger('click')
  await drag(handle, 'pointermove', 200)
  expect(sheetHeight()).toBe('513px')
})

it('clamps at the top navigation and keeps the handle accessible at minimum height', async () => {
  await open()
  const handle = wrapper.get('.sheet-resize-handle')
  await drag(handle, 'pointerdown', 400)
  await drag(handle, 'pointermove', -1000)
  expect(sheetHeight()).toBe('748px')
  await drag(handle, 'pointermove', 2000)
  expect(sheetHeight()).toBe('64px')
  await drag(handle, 'pointercancel', 2000)
  await drag(handle, 'pointermove', 400)
  expect(sheetHeight()).toBe('64px')
})

it('supports keyboard adjustment and adapts to viewport changes', async () => {
  await open()
  const handle = wrapper.get('.sheet-resize-handle')
  await handle.trigger('keydown', { key: 'ArrowUp' })
  expect(Number.parseFloat(sheetHeight())).toBeCloseTo(464)
  height = 600
  resize()
  await wrapper.vm.$nextTick()
  expect(Number.parseFloat(sheetHeight())).toBeCloseTo(348)
  await handle.trigger('keydown', { key: 'End' })
  expect(sheetHeight()).toBe('548px')
})
