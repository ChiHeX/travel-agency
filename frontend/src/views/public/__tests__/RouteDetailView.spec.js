// @vitest-environment jsdom
import { beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import RouteDetailView from '../RouteDetailView.vue'

const { api } = vi.hoisted(() => ({ api: { detail: vi.fn(), reviews: vi.fn() } }))
vi.mock('@/api/modules', () => ({ routeApi: api, accountApi: {} }))
vi.mock('@/stores/auth', () => ({ useAuthStore: () => ({ isLoggedIn: false }) }))
vi.mock('vue-router', () => ({ useRoute: () => ({ params: { id: '7' } }), useRouter: () => ({}) }))
beforeEach(() => {
  vi.resetAllMocks()
  api.detail.mockResolvedValue({ route: { id: '7', name: '测试线路' }, departures: [], itinerary: [], reviews: [{ id: 'old', content: '内嵌评价不作为分页数据' }] })
  api.reviews.mockResolvedValue({ items: [{ id: '1', content: '第一页评价' }], total: 11 })
})
it('使用分页接口并在第二页失败后重试当前页', async () => {
  const wrapper = mount(RouteDetailView, { global: { stubs: {
    RouterLink: { template: '<a><slot /></a>' }, AppIcon: true, PanelIconButton: true,
    ElRate: true, ElSkeleton: true,
    ElPagination: { props: ['currentPage'], emits: ['current-change'], template: '<button class="next" @click="$emit(\'current-change\', 2)">下一页</button>' }
  } } })
  await flushPromises()
  expect(api.reviews).toHaveBeenCalledWith('7', { page: 1, size: 10 })
  expect(wrapper.text()).toContain('第一页评价')
  expect(wrapper.text()).not.toContain('内嵌评价不作为分页数据')
  api.reviews.mockRejectedValueOnce(new Error('评价请求失败'))
  await wrapper.find('.next').trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('评价请求失败')
  api.reviews.mockResolvedValueOnce({ items: [{ id: '2', content: '第二页评价' }], total: 11 })
  await wrapper.findAll('button').find((button) => button.text() === '重新加载').trigger('click')
  await flushPromises()
  expect(api.reviews).toHaveBeenLastCalledWith('7', { page: 2, size: 10 })
  expect(wrapper.text()).toContain('第二页评价')
  expect(wrapper.text()).not.toContain('第一页评价')
})
