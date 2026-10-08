// @vitest-environment jsdom
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import PaymentResultView from '../PaymentResultView.vue'
const { api } = vi.hoisted(() => ({ api: { detail: vi.fn() } }))
vi.mock('@/api/modules', () => ({ orderApi: api }))
vi.mock('vue-router', () => ({ useRoute: () => ({ params: { orderNo: 'TEST' }, query: { trade_status: 'TRADE_SUCCESS' } }), useRouter: () => ({ push: vi.fn() }) }))
const detail = status => ({ order: { orderNo: 'TEST', status: status === 'PAID' ? 'PAID_WAIT_CONFIRM' : 'WAIT_PAY', paymentStatus: status }, payment: { status }, route: { name: '测试线路' } })
let wrapper
beforeEach(() => { vi.resetAllMocks(); vi.useFakeTimers() })
afterEach(() => { wrapper?.unmount(); vi.useRealTimers() })
const open = async () => { wrapper = mount(PaymentResultView, { global: { stubs: { ElSkeleton: true } } }); await flushPromises(); return wrapper }

it('ignores success return parameters and waits for server confirmation', async () => {
  api.detail.mockResolvedValueOnce(detail('PENDING')).mockResolvedValueOnce(detail('PAID'))
  await open()
  expect(wrapper.text()).toContain('正在确认支付结果')
  await vi.advanceTimersByTimeAsync(3000)
  await flushPromises()
  expect(wrapper.text()).toContain('支付已确认')
})
it('shows query failure without pretending payment failed and allows retry', async () => {
  api.detail.mockRejectedValueOnce(new Error('查询失败')).mockResolvedValueOnce(detail('PAID'))
  await open()
  expect(wrapper.text()).toContain('暂时无法查询支付结果')
  await wrapper.findAll('button').find(b => b.text() === '重新查询').trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('支付已确认')
})
it('refresh and reopening read payment state from the server', async () => {
  api.detail.mockResolvedValue(detail('PAID'))
  await open(); wrapper.unmount(); await open()
  expect(api.detail).toHaveBeenCalledTimes(2)
  expect(wrapper.text()).toContain('支付已确认')
})
