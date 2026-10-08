// @vitest-environment jsdom
import { beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import OrderDetailView from '../OrderDetailView.vue'
const { api } = vi.hoisted(() => ({ api: { detail: vi.fn(), itinerary: vi.fn() } }))
vi.mock('@/api/modules', () => ({ orderApi: api }))
vi.mock('vue-router', () => ({ useRoute: () => ({ params: { orderNo: 'TEST' } }), useRouter: () => ({ push: vi.fn() }) }))
vi.mock('element-plus', () => ({ ElMessage: { success: vi.fn() } }))
beforeEach(() => {
  vi.resetAllMocks()
  api.detail.mockResolvedValue({ order: { status: 'CONFIRMED', orderNo: 'TEST' }, travelers: [], refunds: [], departure: {} })
  api.itinerary.mockResolvedValue([{ id: '1', dayNumber: 1, title: '每日安排', items: [] }])
})
const open = async () => {
  const wrapper = mount(OrderDetailView, { global: { stubs: {
    ElSkeleton: true, ElDialog: true, ElRate: true, RouterLink: true, CancelOrderButton: true,
    RouteItinerary: { props: ['days'], template: '<div>{{ days.map(d => d.title).join() }}</div>' }
  } } })
  await flushPromises()
  return wrapper
}
it('opens the protected order itinerary without depending on public route visibility', async () => {
  const wrapper = await open()
  await wrapper.findAll('button').find(b => b.text() === '查看行程').trigger('click')
  await flushPromises()
  expect(api.itinerary).toHaveBeenCalledWith('TEST')
  expect(wrapper.text()).toContain('每日安排')
  expect(wrapper.text()).toContain('不是下单时快照')
  wrapper.unmount()
})
it('shows itinerary failure and allows retry', async () => {
  api.itinerary.mockRejectedValueOnce(new Error('行程查询失败'))
  const wrapper = await open()
  await wrapper.findAll('button').find(b => b.text() === '查看行程').trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('行程查询失败')
  await wrapper.findAll('button').find(b => b.text() === '刷新行程').trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('每日安排')
  wrapper.unmount()
})
it('displays refund processing, rejection and audit comments from refund records', async () => {
  api.detail.mockResolvedValue({ order: { status: 'REFUND_APPLYING' }, travelers: [], departure: {}, refunds: [
    { id: '1', status: 'PROCESSING', reason: '申请退款', reviewComment: '出款待确认' },
    { id: '2', status: 'REJECTED', reason: '申请退款', reviewComment: '审核拒绝原因' }
  ] })
  const wrapper = await open()
  expect(wrapper.text()).toContain('退款处理中')
  expect(wrapper.text()).toContain('审核未通过')
  expect(wrapper.text()).toContain('出款待确认')
  expect(wrapper.text()).toContain('审核拒绝原因')
  expect(wrapper.text()).not.toContain('查看行程')
  wrapper.unmount()
})
