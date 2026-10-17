// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import Orders from '../AdminOrdersView.vue'
import Resources from '../AdminResourcesView.vue'
import Articles from '../AdminArticlesView.vue'
import Consultations from '../AdminConsultationsView.vue'
import Logs from '../AdminLogsView.vue'
import Reviews from '../AdminReviewsView.vue'
import Users from '../AdminUsersView.vue'

const { api, prompt, confirm } = vi.hoisted(() => ({
  api: Object.fromEntries(['orders', 'order', 'confirmOrder', 'refunds', 'refund', 'approveRefund', 'rejectRefund', 'departures', 'routes', 'guides', 'hotels', 'attractions', 'articles', 'consultations', 'logs', 'reviews', 'staff', 'users'].map(k => [k, vi.fn()])),
  prompt: vi.fn(), confirm: vi.fn()
}))
vi.mock('@/api/modules', () => ({ adminApi: api }))
vi.mock('@/stores/auth', () => ({ useAuthStore: () => ({ hasRole: () => true }) }))
vi.mock('element-plus', () => ({ ElMessage: { success: vi.fn(), warning: vi.fn(), error: vi.fn() }, ElMessageBox: { prompt, confirm } }))
const wrappers = []
const DialogStub = { props: ['modelValue'], emits: ['update:modelValue', 'close'], template: '<div v-if="modelValue"><button @click="$emit(\'update:modelValue\',false);$emit(\'close\')">关闭详情</button><slot/></div>' }
function view(component, props = {}) {
  const w = mount(component, { props, global: { stubs: {
    'el-dialog': DialogStub, 'el-skeleton': true, 'el-tooltip': { template: '<span><slot/></span>' },
    AttractionFormDialog: true, DepartureFormDialog: true, GuideFormDialog: true, HotelFormDialog: true, StaffFormDialog: true
  } } }); wrappers.push(w); return w
}
function button(w, text) { return w.findAll('button').find(b => b.text() === text) }
function deferred() { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b }); return { promise, resolve, reject } }
const page = items => ({ items, total: items.length, totalPages: 1 })
const row = name => ({ id: name, username: name, title: name, name, module: name, operatorName: name, userNickname: name, routeName: name, orderNo: name, status: 'ACTIVE', result: 'SUCCESS' })
beforeEach(() => { vi.resetAllMocks(); Object.values(api).forEach(fn => fn.mockResolvedValue(page([]))); prompt.mockResolvedValue({ value: '审核通过' }); confirm.mockResolvedValue('confirm') })
afterEach(() => wrappers.splice(0).forEach(w => w.unmount()))

describe('后台列表竞态', () => {
  it.each([
    ['订单', Orders, 'orders', {}], ['退款', Resources, 'refunds', { title: '退款审核', resource: 'refunds' }],
    ['团期', Resources, 'departures', { title: '团期', resource: 'departures' }],
    ['用户', Users, 'users', {}], ['员工', Users, 'staff', { mode: 'staff' }],
    ['评价', Reviews, 'reviews', {}], ['咨询', Consultations, 'consultations', {}],
    ['攻略', Articles, 'articles', {}], ['日志', Logs, 'logs', {}]
  ])('%s：旧失败不清空新结果，旧请求也不提前结束加载', async (_name, component, method, props) => {
    const a = deferred(), b = deferred(), c = deferred()
    api[method].mockReturnValueOnce(a.promise).mockReturnValueOnce(b.promise).mockReturnValueOnce(c.promise)
    const w = view(component, props); await w.get('form').trigger('submit')
    a.resolve(page([row('old-result')])); await flushPromises()
    expect(w.text()).not.toContain('old-result')
    await w.get('form').trigger('submit'); c.resolve(page([row('new-result')])); await flushPromises()
    b.reject(new Error('stale-error')); await flushPromises()
    expect(w.text()).toContain('new-result'); expect(w.text()).not.toContain('stale-error')
  })
})
describe('订单与退款', () => {
  it('订单筛选只列八个实际业务状态，按筛选发请求', async () => {
    const w = view(Orders); await flushPromises()
    const values = w.findAll('select option').map(o => o.element.value).filter(Boolean)
    expect(values).toEqual(['WAIT_PAY', 'PAID_WAIT_CONFIRM', 'CONFIRMED', 'TRAVELLING', 'COMPLETED', 'CANCELLED', 'REFUND_APPLYING', 'REFUNDED'])
    await w.get('select').setValue('REFUND_APPLYING'); await w.get('form').trigger('submit'); await flushPromises()
    expect(api.orders.mock.calls.at(-1)[0].status).toBe('REFUND_APPLYING')
  })
  it('确认报名取消不提交', async () => {
    api.orders.mockResolvedValue(page([{ ...row('A'), status: 'PAID_WAIT_CONFIRM' }]))
    confirm.mockRejectedValueOnce('cancel'); const w = view(Orders); await flushPromises()
    await button(w, '确认报名').trigger('click'); await flushPromises(); expect(api.confirmOrder).not.toHaveBeenCalled()
  })
  it('订单详情关闭后，旧响应不能替换新详情', async () => {
    api.orders.mockResolvedValue(page([row('A'), row('B')]))
    const a = deferred(), b = deferred(); api.order.mockReturnValueOnce(a.promise).mockReturnValueOnce(b.promise)
    const w = view(Orders); await flushPromises()
    await w.findAll('tbody tr')[0].find('button').trigger('click'); await button(w, '关闭详情').trigger('click')
    await w.findAll('tbody tr')[1].find('button').trigger('click')
    b.resolve({ order: { ...row('B'), status: 'CONFIRMED' }, route: {}, departure: {}, travelers: [], refunds: [] }); await flushPromises()
    a.resolve({ order: { ...row('A'), status: 'CONFIRMED' }, route: {}, departure: {}, travelers: [], refunds: [] }); await flushPromises()
    expect(w.get('.order-detail').text()).toContain('B'); expect(w.get('.order-detail').text()).not.toContain('A')
  })
  it('退款意见校验空白及码点上限，取消不提交', async () => {
    api.refunds.mockResolvedValue(page([{ ...row('R1'), status: 'APPLYING', amount: '10.00' }]))
    prompt.mockRejectedValueOnce('cancel'); const w = view(Resources, { title: '退款', resource: 'refunds' }); await flushPromises()
    await button(w, '同意').trigger('click'); await flushPromises()
    const validator = prompt.mock.calls[0][2].inputValidator
    expect(validator('  ')).not.toBe(true); expect(validator('😀'.repeat(500))).toBe(true); expect(validator('字'.repeat(501))).not.toBe(true)
    expect(api.approveRefund).not.toHaveBeenCalled()
  })
  it('人工意见提交且防重复；结果未确认后刷新为仅能重试的 PROCESSING', async () => {
    const applying = { ...row('R1'), status: 'APPLYING', amount: '10.00' }
    api.refunds.mockResolvedValueOnce(page([applying])).mockResolvedValue(page([{ ...applying, status: 'PROCESSING' }]))
    const pending = deferred(); api.approveRefund.mockReturnValueOnce(pending.promise)
    prompt.mockResolvedValue({ value: '  核实后同意  ' })
    const w = view(Resources, { title: '退款', resource: 'refunds' }); await flushPromises()
    await button(w, '同意').trigger('click'); await flushPromises()
    expect(api.approveRefund).toHaveBeenCalledWith('R1', '核实后同意')
    expect(button(w, '同意').element.disabled).toBe(true)
    pending.reject(Object.assign(new Error('结果未确认'), { status: 503, code: 'REFUND_RESULT_UNCONFIRMED' })); await flushPromises()
    expect(button(w, '拒绝')).toBeUndefined(); expect(button(w, '重试确认')).toBeDefined()
    await button(w, '重试确认').trigger('click'); await flushPromises()
    expect(api.approveRefund).toHaveBeenCalledTimes(2); expect(api.rejectRefund).not.toHaveBeenCalled()
  })
})
