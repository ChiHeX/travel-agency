// @vitest-environment jsdom
import { beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import OrderCreateView from '../OrderCreateView.vue'
const { account, routes } = vi.hoisted(() => ({ account: { travelers: vi.fn() }, routes: { detail: vi.fn() } }))
vi.mock('@/api/modules', () => ({ accountApi: account, routeApi: routes, orderApi: {} }))
vi.mock('vue-router', () => ({ useRoute: () => ({ query: { routeId: '1', departureId: '2' } }), useRouter: () => ({ push: vi.fn(), replace: vi.fn() }) }))
vi.mock('element-plus', () => ({ ElMessage: { warning: vi.fn() }, ElMessageBox: {} }))
beforeEach(() => {
  vi.resetAllMocks()
  routes.detail.mockResolvedValue({ route: { name: '测试线路' }, departures: [{ id: '2', status: 'OPEN', availableSeats: 10, adultPrice: '100.00' }] })
})
it('distinguishes saved traveler failure, permits manual entry, and retries without resetting the draft', async () => {
  account.travelers.mockRejectedValueOnce(new Error('网络异常')).mockResolvedValueOnce([{ id: '3', name: '测试旅客' }])
  const wrapper = mount(OrderCreateView, { global: { plugins: [createPinia()], stubs: { PanelIconButton: true, ElSkeleton: true } } })
  await flushPromises()
  expect(wrapper.text()).toContain('常用出行人加载失败')
  expect(wrapper.text()).toContain('仍可手工填写')
  expect(wrapper.findAll('.traveler-entry input').length).toBeGreaterThan(0)
  const input = wrapper.find('.traveler-entry input')
  await input.setValue('手工填写内容')
  await wrapper.findAll('button').find(b => b.text() === '重新加载').trigger('click')
  await flushPromises()
  expect(wrapper.text()).not.toContain('常用出行人加载失败')
  expect(wrapper.text()).toContain('测试旅客')
  expect(input.element.value).toBe('手工填写内容')
  expect(routes.detail).toHaveBeenCalledOnce()
  wrapper.unmount()
})

it.each(['success', 'failure'])('permits manual entry while saved travelers are pending and preserves it after %s', async (outcome) => {
  let resolveTravelers
  let rejectTravelers
  account.travelers.mockReturnValueOnce(new Promise((resolve, reject) => {
    resolveTravelers = resolve
    rejectTravelers = reject
  }))
  const wrapper = mount(OrderCreateView, { global: { plugins: [createPinia()], stubs: { PanelIconButton: true, ElSkeleton: true } } })
  await flushPromises()
  expect(wrapper.find('.booking-grid').exists()).toBe(true)
  expect(wrapper.find('.checkout-submit-btn').element.disabled).toBe(false)
  const input = wrapper.find('.traveler-entry input')
  await input.setValue('手工填写内容')

  if (outcome === 'success') resolveTravelers([{ id: '3', name: '测试旅客' }])
  else rejectTravelers(new Error('网络异常'))
  await flushPromises()

  expect(input.element.value).toBe('手工填写内容')
  expect(wrapper.find('.booking-grid').exists()).toBe(true)
  if (outcome === 'success') expect(wrapper.text()).toContain('测试旅客')
  else expect(wrapper.text()).toContain('常用出行人加载失败')
  wrapper.unmount()
})
