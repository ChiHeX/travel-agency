// @vitest-environment jsdom
import { beforeEach, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import PaymentView from '../PaymentView.vue'

const { api, push, error } = vi.hoisted(() => ({
  api: { detail: vi.fn(), paymentOptions: vi.fn(), simulatePayment: vi.fn(), pay: vi.fn() },
  push: vi.fn(), error: vi.fn()
}))
vi.mock('@/api/modules', () => ({ orderApi: api }))
vi.mock('vue-router', () => ({ useRoute: () => ({ params: { orderNo: 'TEST-ORDER' } }), useRouter: () => ({ push }) }))
vi.mock('element-plus', () => ({ ElMessage: { error } }))

beforeEach(() => {
  vi.resetAllMocks()
  api.detail.mockResolvedValue({
    order: { orderNo: 'TEST-ORDER', status: 'WAIT_PAY', adultCount: 1, totalAmount: '123.45' },
    payment: { status: 'UNPAID' }, route: { name: '测试行程' }, departure: {}
  })
  api.paymentOptions.mockResolvedValue({ localSimulationEnabled: true })
})
async function open() {
  const wrapper = mount(PaymentView, { global: { stubs: { RouterLink: true, ElSkeleton: true } } })
  await flushPromises()
  return wrapper
}

it('hides local payment unless the server explicitly enables it', async () => {
  api.paymentOptions.mockResolvedValue({ localSimulationEnabled: false })
  const wrapper = await open()
  expect(wrapper.find('input[value="local"]').exists()).toBe(false)
  expect(wrapper.get('.pay-button').text()).toBe('前往支付宝付款')
})

it('submits only the order number, blocks repeated clicks, then opens server-backed results', async () => {
  let resolve
  api.simulatePayment.mockReturnValue(new Promise(r => { resolve = r }))
  const wrapper = await open()
  await wrapper.get('.pay-button').trigger('click')
  expect(wrapper.get('.pay-button').element.disabled).toBe(true)
  await wrapper.get('.pay-button').trigger('click')
  expect(api.simulatePayment).toHaveBeenCalledExactlyOnceWith('TEST-ORDER')
  expect(push).not.toHaveBeenCalled()
  resolve({ status: 'PAID' })
  await flushPromises()
  expect(push).toHaveBeenCalledWith({ name: 'order-payment-result', params: { orderNo: 'TEST-ORDER' } })
  expect(api.pay).not.toHaveBeenCalled()
})

it('keeps failed simulation on checkout and allows retry', async () => {
  api.simulatePayment.mockRejectedValueOnce(new Error('订单状态已变化'))
  const wrapper = await open()
  await wrapper.get('.pay-button').trigger('click')
  await flushPromises()
  expect(error).toHaveBeenCalledWith('订单状态已变化')
  expect(push).not.toHaveBeenCalled()
  expect(wrapper.get('.pay-button').element.disabled).toBe(false)
  expect(api.pay).not.toHaveBeenCalled()
})

it('never falls back to simulation when the selected Alipay request fails', async () => {
  api.pay.mockRejectedValueOnce(new Error('支付宝未配置'))
  const wrapper = await open()
  await wrapper.get('input[value="alipay"]').setValue()
  await wrapper.get('.pay-button').trigger('click')
  await flushPromises()
  expect(error).toHaveBeenCalledWith('支付宝未配置')
  expect(api.simulatePayment).not.toHaveBeenCalled()
})

it('disables local payment after Alipay has already started', async () => {
  const detail = await api.detail()
  detail.payment.status = 'PENDING'
  api.detail.mockResolvedValue(detail)
  const wrapper = await open()
  expect(wrapper.get('input[value="local"]').element.disabled).toBe(true)
  expect(wrapper.get('.pay-button').text()).toBe('前往支付宝付款')
})
