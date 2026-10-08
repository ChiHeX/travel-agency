// @vitest-environment jsdom
import { beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import OrdersView from '../OrdersView.vue'
import FavoritesView from '../FavoritesView.vue'

const { api } = vi.hoisted(() => ({ api: { list: vi.fn(), favorites: vi.fn() } }))
vi.mock('@/api/modules', () => ({ orderApi: api, accountApi: api }))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }))
vi.mock('element-plus', () => ({ ElMessage: { success: vi.fn() } }))
beforeEach(() => vi.resetAllMocks())
const stubs = {
  ElSkeleton: true, CancelOrderButton: true, PanelIconButton: true,
  RouteCard: { props: ['route'], template: '<div>{{ route.name }}</div>' },
  ElPagination: { name: 'TestPagination', emits: ['current-change'], template: '<button class="next-page" @click="$emit(\'current-change\', 2)">下一页</button>' }
}

it.each(['success', 'error'])('orders ignore stale %s after switching status', async outcome => {
  let resolve, reject
  api.list.mockReturnValueOnce(new Promise((a, b) => { resolve = a; reject = b }))
  const wrapper = mount(OrdersView, { global: { stubs } })
  api.list.mockResolvedValueOnce({ items: [{ id: '2', routeName: '已确认新结果', status: 'CONFIRMED' }], total: 1 })
  await wrapper.findAll('.status-tab-btn').find(b => b.text() === '已确认').trigger('click')
  await flushPromises()
  if (outcome === 'success') resolve({ items: [{ id: '1', routeName: '旧结果' }], total: 1 })
  else reject(new Error('过期错误'))
  await flushPromises()
  expect(wrapper.text()).toContain('已确认新结果')
  expect(wrapper.text()).not.toContain('旧结果')
  expect(wrapper.text()).not.toContain('过期错误')
  expect(wrapper.findAll('.status-tab-btn').map(b => b.text())).not.toContain('退款处理中')
  expect(wrapper.findAll('.status-tab-btn').map(b => b.text())).not.toContain('退款未通过')
  wrapper.unmount()
})

it('favorites ignore a stale page response', async () => {
  api.favorites.mockResolvedValueOnce({ items: [{ id: '1', name: '第一页' }], total: 30 })
  const wrapper = mount(FavoritesView, { global: { stubs } })
  await flushPromises()
  let resolve
  const pagination = wrapper.findComponent({ name: 'TestPagination' })
  api.favorites.mockReturnValueOnce(new Promise(r => { resolve = r }))
  await wrapper.get('.next-page').trigger('click')
  // A retry starts another request for the most recent page.
  api.favorites.mockResolvedValueOnce({ items: [{ id: '3', name: '最新收藏' }], total: 30 })
  pagination.vm.$emit('current-change', 3)
  await flushPromises()
  resolve({ items: [{ id: '2', name: '过期收藏' }], total: 30 })
  await flushPromises()
  expect(wrapper.text()).toContain('最新收藏')
  expect(wrapper.text()).not.toContain('过期收藏')
  wrapper.unmount()
})
