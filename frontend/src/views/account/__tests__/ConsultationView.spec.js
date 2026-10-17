// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ConsultationView from '../ConsultationView.vue'

const { api, confirm } = vi.hoisted(() => ({
  api: { consultations: vi.fn(), consultation: vi.fn(), closeConsultation: vi.fn(), deleteConsultation: vi.fn() },
  confirm: vi.fn()
}))
vi.mock('@/api/modules', () => ({ accountApi: api }))
vi.mock('element-plus', () => ({ ElMessage: { success: vi.fn() }, ElMessageBox: { confirm } }))
const consultation = { id: '1', title: '集合地点', content: '请问在哪里集合？', status: 'WAIT_REPLY', replies: [] }
function button(wrapper, text) { return wrapper.findAll('button').find((item) => item.text().replace(' →', '') === text) }
async function open() {
  const wrapper = mount(ConsultationView, { global: { stubs: {
    ElDialog: { props: ['modelValue'], template: '<div v-if="modelValue"><slot /><slot name="footer" /></div>' },
    ElSkeleton: true, ElPagination: true
  } } })
  await flushPromises()
  await button(wrapper, '查看详情').trigger('click')
  await flushPromises()
  return wrapper
}
beforeEach(() => {
  vi.resetAllMocks()
  api.consultations.mockResolvedValue({ items: [{ ...consultation }], total: 1 })
  api.consultation.mockResolvedValue({ ...consultation, replies: [{ id: 'r', staffName: '顾问', content: '车站集合', createdAt: '2026-10-03' }] })
  api.closeConsultation.mockResolvedValue({ ...consultation, status: 'CLOSED' })
  confirm.mockResolvedValue(undefined)
})
describe('用户咨询', () => {
  it('列表仅为已关闭咨询显示删除，直接确认删除无需打开详情', async () => {
    api.consultations.mockResolvedValue({ items: [consultation, { ...consultation, id: '2', status: 'CLOSED' }], total: 2 })
    const wrapper = mount(ConsultationView, { global: { stubs: { ElDialog: true, ElSkeleton: true, ElPagination: true } } })
    await flushPromises()
    const cards = wrapper.findAll('.history-card')
    expect(cards[0].find('.consult-delete-button').exists()).toBe(false)
    expect(cards[1].findAll('button').map((item) => item.text())).toEqual(['查看详情', '删除'])
    api.consultations.mockResolvedValue({ items: [consultation], total: 1 })
    await cards[1].find('.consult-delete-button').trigger('click')
    await flushPromises()
    expect(api.consultation).not.toHaveBeenCalled()
    expect(api.deleteConsultation).toHaveBeenCalledWith('2')
    expect(wrapper.findAll('.history-card')).toHaveLength(1)
  })
  it('仅关闭后可删除，取消不发送请求，失败可重试，成功刷新列表', async () => {
    api.consultation.mockResolvedValue({ ...consultation, status: 'CLOSED' })
    const wrapper = await open()
    confirm.mockRejectedValueOnce('cancel')
    await button(wrapper, '删除咨询').trigger('click')
    await flushPromises()
    expect(api.deleteConsultation).not.toHaveBeenCalled()
    api.deleteConsultation.mockRejectedValueOnce(new Error('删除失败'))
    await button(wrapper, '删除咨询').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('删除失败')
    api.consultations.mockResolvedValue({ items: [], total: 0 })
    await button(wrapper, '删除咨询').trigger('click')
    await flushPromises()
    expect(api.deleteConsultation).toHaveBeenLastCalledWith('1')
    expect(wrapper.text()).toContain('暂无历史咨询记录')
  })
  it('详情加载最新回复，关闭成功后同步列表并隐藏关闭按钮', async () => {
    const wrapper = await open()
    expect(api.consultation).toHaveBeenCalledWith('1')
    expect(wrapper.text()).toContain('车站集合')
    await button(wrapper, '关闭咨询').trigger('click')
    await flushPromises()
    expect(api.closeConsultation).toHaveBeenCalledWith('1')
    expect(confirm).toHaveBeenCalledWith(expect.any(String), '关闭咨询', expect.objectContaining({ customClass: 'order-cancel-confirm' }))
    expect(wrapper.find('.history-card').text()).toContain('已关闭')
    expect(button(wrapper, '关闭咨询')).toBeUndefined()
  })
  it('取消确认不关闭咨询，失败后保留状态并允许重试', async () => {
    const wrapper = await open()
    confirm.mockRejectedValueOnce('cancel')
    await button(wrapper, '关闭咨询').trigger('click')
    await flushPromises()
    expect(api.closeConsultation).not.toHaveBeenCalled()
    api.closeConsultation.mockRejectedValueOnce(new Error('服务暂不可用'))
    await button(wrapper, '关闭咨询').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('服务暂不可用')
    expect(wrapper.find('.history-card').text()).toContain('等待回复')
    await button(wrapper, '关闭咨询').trigger('click')
    await flushPromises()
    expect(wrapper.find('.history-card').text()).toContain('已关闭')
  })
  it('详情请求失败可以重试', async () => {
    api.consultation.mockRejectedValueOnce(new Error('详情获取失败'))
    const wrapper = await open()
    expect(wrapper.text()).toContain('详情获取失败')
    await button(wrapper, '重新加载').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('车站集合')
  })
})
